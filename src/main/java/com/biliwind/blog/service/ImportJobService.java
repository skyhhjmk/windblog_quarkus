package com.biliwind.blog.service;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportProgressEvent;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminImportDtos.ImportResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Durable queue, lease watchdog, and bounded retry policy for administrator imports. */
@ApplicationScoped
public class ImportJobService {
    private static final Logger LOG = Logger.getLogger(ImportJobService.class);

    @Inject EntityManager entityManager;
    @Inject ObjectMapper objectMapper;
    @Inject ImportService importService;
    @Inject Instance<ImportJobService> selfProxy;

    @ConfigProperty(name = "admin.jwt.secret")
    String encryptionSecret;

    @ConfigProperty(name = "media.upload.dir", defaultValue = "uploads")
    String uploadDirectory;

    @Transactional
    public Map<String, Object> enqueue(ImportRequest request, Path sqlArtifact, Long operatorId) {
        if (request == null || operatorId == null) throw new IllegalArgumentException("导入请求或操作用户无效");
        if (request.clearExisting()) throw new IllegalArgumentException("当前导入不支持清空模式");
        UUID jobId = UUID.randomUUID();
        String artifactPath = null;
        try {
            if (sqlArtifact != null) {
                Path jobRoot = Path.of(uploadDirectory).toAbsolutePath().normalize().resolve(".import-jobs");
                Files.createDirectories(jobRoot);
                Path staged = jobRoot.resolve(jobId + ".sql");
                Files.copy(sqlArtifact, staged);
                artifactPath = staged.toString();
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("encryptedRequest", encrypt(objectMapper.writeValueAsString(new ImportRequest(
                    request.driver(), request.url(), request.username(), request.password(),
                    request.types(), request.assetPrefix(), false))));
            entityManager.createNativeQuery("insert into import_jobs (id, request_payload, sql_artifact_path, operator_id) "
                            + "values (?1, cast(?2 as jsonb), ?3, ?4)")
                    .setParameter(1, jobId).setParameter(2, objectMapper.writeValueAsString(payload))
                    .setParameter(3, artifactPath).setParameter(4, operatorId).executeUpdate();
            return status(jobId.toString());
        } catch (Exception exception) {
            if (artifactPath != null) try { Files.deleteIfExists(Path.of(artifactPath)); } catch (Exception ignored) { }
            throw new IllegalStateException("无法暂存导入任务", exception);
        }
    }

    @Transactional
    public Map<String, Object> status(String id) {
        Object[] row;
        try {
            row = (Object[]) entityManager.createNativeQuery("select status, attempt_count, max_attempts, "
                    + "progress_payload::text, result_payload::text, last_error, created_at, updated_at "
                    + "from import_jobs where id = cast(?1 as uuid)").setParameter(1, id).getSingleResult();
        } catch (jakarta.persistence.NoResultException exception) {
            throw new IllegalArgumentException("导入任务不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", id);
        result.put("status", row[0]);
        result.put("attempt", row[1]);
        result.put("maxAttempts", row[2]);
        result.put("progress", parseJson(row[3]));
        result.put("result", parseJson(row[4]));
        result.put("error", row[5]);
        result.put("createdAt", row[6]);
        result.put("updatedAt", row[7]);
        return result;
    }

    @Transactional
    public void recordProgress(String jobId, ImportProgressEvent event) {
        if (!"overall".equals(event.type()) && !"download".equals(event.type())
                && !"end".equals(event.type()) && !"error".equals(event.type())) return;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("type", event.type()); snapshot.put("message", event.message());
        snapshot.put("data", event.data()); snapshot.put("status", event.status());
        try {
            entityManager.createNativeQuery("update import_jobs set progress_payload=cast(?2 as jsonb), "
                            + "heartbeat_at=now(), updated_at=now() where id=cast(?1 as uuid) and status='RUNNING'")
                    .setParameter(1, jobId).setParameter(2, objectMapper.writeValueAsString(snapshot)).executeUpdate();
        } catch (Exception exception) {
            LOG.debug("Unable to persist import progress", exception);
        }
    }

    @Scheduled(every = "5s", identity = "windblog-import-job-worker")
    void runNext() {
        String owner = UUID.randomUUID().toString();
        UUID id;
        try { id = selfProxy.get().claim(owner); } catch (Exception exception) {
            LOG.debug("Import job claim failed", exception); return;
        }
        if (id == null) return;
        try {
            JobPayload payload = selfProxy.get().load(id);
            ImportResult result = payload.sqlArtifactPath() == null
                    ? importService.runJob(payload.request(), payload.operatorId(), id.toString())
                    : importService.runSqlJob(Path.of(payload.sqlArtifactPath()), payload.request(),
                            payload.operatorId(), id.toString());
            if (result.success()) selfProxy.get().complete(id, result);
            else selfProxy.get().retryOrFail(id, result.message());
        } catch (Exception exception) {
            selfProxy.get().retryOrFail(id, SensitiveMessageSanitizer.sanitize(exception.getMessage()));
            LOG.warnf("Import job attempt failed, id=%s", id);
        }
    }

    @Scheduled(every = "30s", identity = "windblog-import-job-watchdog")
    @Transactional
    void recoverStaleJobs() {
        entityManager.createNativeQuery("update import_jobs set status=case when attempt_count < max_attempts "
                        + "then 'RETRY' else 'FAILED' end, available_at=now(), lock_owner=null, locked_until=null, "
                        + "last_error='导入任务租约超时，由看门狗回收', updated_at=now(), "
                        + "request_payload=case when attempt_count >= max_attempts then '{}'::jsonb else request_payload end, "
                        + "completed_at=case when attempt_count >= max_attempts then now() else completed_at end "
                        + "where status='RUNNING' and heartbeat_at < now() - interval '5 minutes'")
                .executeUpdate();
    }

    @Scheduled(every = "1m", identity = "windblog-import-job-artifact-cleanup")
    @Transactional
    void cleanupTerminalArtifacts() {
        @SuppressWarnings("unchecked")
        var rows = entityManager.createNativeQuery("select id from import_jobs "
                        + "where status in ('SUCCEEDED','FAILED') and sql_artifact_path is not null")
                .getResultList();
        for (Object row : rows) deleteArtifact(UUID.fromString(row.toString()));
    }

    @Transactional
    UUID claim(String owner) {
        @SuppressWarnings("unchecked")
        var rows = entityManager.createNativeQuery("update import_jobs set status='RUNNING', "
                        + "attempt_count=attempt_count+1, lock_owner=?1, locked_until=now()+interval '5 minutes', "
                        + "heartbeat_at=now(), updated_at=now() where id=(select id from import_jobs "
                        + "where status in ('PENDING','RETRY') and available_at <= now() "
                        + "order by created_at for update skip locked limit 1) returning id")
                .setParameter(1, owner).getResultList();
        return rows.isEmpty() ? null : UUID.fromString(rows.get(0).toString());
    }

    @Transactional
    JobPayload load(UUID id) throws Exception {
        Object[] row = (Object[]) entityManager.createNativeQuery("select request_payload::text, "
                        + "sql_artifact_path, operator_id from import_jobs where id=?1")
                .setParameter(1, id).getSingleResult();
        Map<String, Object> map = objectMapper.readValue(row[0].toString(), new TypeReference<>() { });
        String serializedRequest = decrypt(String.valueOf(map.get("encryptedRequest")));
        Map<String, Object> requestMap = objectMapper.readValue(serializedRequest, new TypeReference<>() { });
        return new JobPayload(objectMapper.convertValue(requestMap, ImportRequest.class),
                row[1] == null ? null : row[1].toString(), ((Number) row[2]).longValue());
    }

    @Transactional
    void complete(UUID id, ImportResult result) throws Exception {
        Map<String, Object> body = objectMapper.convertValue(result, new TypeReference<>() { });
        entityManager.createNativeQuery("update import_jobs set status='SUCCEEDED', request_payload='{}'::jsonb, result_payload=cast(?2 as jsonb), "
                        + "last_error=null, lock_owner=null, locked_until=null, updated_at=now(), completed_at=now() where id=?1")
                .setParameter(1, id).setParameter(2, objectMapper.writeValueAsString(body)).executeUpdate();
        deleteArtifact(id);
    }

    @Transactional
    void retryOrFail(UUID id, String error) {
        entityManager.createNativeQuery("update import_jobs set status=case when attempt_count < max_attempts "
                        + "then 'RETRY' else 'FAILED' end, available_at=now()+make_interval(mins => "
                        + "least(30, power(2, greatest(0, attempt_count-1))::int)), last_error=?2, "
                        + "lock_owner=null, locked_until=null, updated_at=now(), "
                        + "request_payload=case when attempt_count >= max_attempts then '{}'::jsonb else request_payload end, "
                        + "completed_at=case when attempt_count >= max_attempts then now() else completed_at end where id=?1")
                .setParameter(1, id).setParameter(2, error == null ? "导入失败" : error).executeUpdate();
        String status = String.valueOf(entityManager.createNativeQuery("select status from import_jobs where id=?1")
                .setParameter(1, id).getSingleResult());
        if ("FAILED".equals(status)) deleteArtifact(id);
    }

    private void deleteArtifact(UUID id) {
        Object rawPath = entityManager.createNativeQuery("select sql_artifact_path from import_jobs where id=?1")
                .setParameter(1, id).getSingleResult();
        if (rawPath == null) return;
        Path root = Path.of(uploadDirectory).toAbsolutePath().normalize().resolve(".import-jobs");
        Path artifact = Path.of(rawPath.toString()).toAbsolutePath().normalize();
        if (!artifact.startsWith(root)) {
            LOG.errorf("Refusing to remove an import artifact outside the staging directory, job=%s", id);
            return;
        }
        try {
            Files.deleteIfExists(artifact);
            entityManager.createNativeQuery("update import_jobs set sql_artifact_path=null where id=?1")
                    .setParameter(1, id).executeUpdate();
        } catch (Exception exception) {
            LOG.warnf("Unable to clean terminal import artifact, job=%s", id);
        }
    }

    private Object parseJson(Object raw) {
        if (raw == null) return null;
        try { return objectMapper.readValue(raw.toString(), Object.class); }
        catch (Exception ignored) { return null; }
    }

    private String encrypt(String value) throws Exception {
        byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return "enc:v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted);
    }

    private String decrypt(String value) throws Exception {
        if (value == null || !value.startsWith("enc:v1:")) throw new IllegalArgumentException("导入任务凭据密文无效");
        String[] parts = value.substring(7).split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }

    private SecretKeySpec key() throws Exception {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                .digest(encryptionSecret.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public record JobPayload(ImportRequest request, String sqlArtifactPath, Long operatorId) { }
}
