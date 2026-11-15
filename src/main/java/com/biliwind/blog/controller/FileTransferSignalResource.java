package com.biliwind.blog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral, zero-persistence in-memory signaling relay for Easy Mode P2P file transfer.
 * Stores only WebRTC SDP Offer/Answer tokens for up to 15 minutes; file bytes never touch the server.
 */
@Path("/api/public/ft-signal")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class FileTransferSignalResource {

    private static final long ROOM_TTL_MS = 15 * 60 * 1000L;
    private static final int MAX_ROOMS = 512;
    private static final int MAX_TOKEN_LENGTH = 32768;
    private static final String CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, SignalRoom> rooms = new ConcurrentHashMap<>();

    @POST
    @Path("/rooms")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createRoom(JsonNode body) {
        cleanupExpired();
        if (rooms.size() >= MAX_ROOMS) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "BUSY", "message", "Too many active pairing rooms"))
                    .build();
        }
        String offer = body != null && body.path("offer").isTextual() ? body.path("offer").asText().trim() : "";
        if (offer.isEmpty() || offer.length() > MAX_TOKEN_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "INVALID_OFFER", "message", "Valid offer token is required"))
                    .build();
        }
        String roomId = randomCode(10);
        String hostToken = randomCode(20);
        rooms.put(roomId, new SignalRoom(offer, hostToken, System.currentTimeMillis() + ROOM_TTL_MS));
        return Response.status(Response.Status.CREATED)
                .entity(Map.of("roomId", roomId, "hostToken", hostToken))
                .header("Cache-Control", "no-store")
                .build();
    }

    @GET
    @Path("/rooms/{roomId}")
    public Response getOffer(@PathParam("roomId") String roomId) {
        cleanupExpired();
        SignalRoom room = rooms.get(roomId);
        if (room == null || room.isExpired()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "NOT_FOUND", "message", "Pairing link expired or not found"))
                    .build();
        }
        return Response.ok(Map.of("offer", room.offer))
                .header("Cache-Control", "no-store")
                .build();
    }

    @POST
    @Path("/rooms/{roomId}/answer")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submitAnswer(@PathParam("roomId") String roomId, JsonNode body) {
        cleanupExpired();
        SignalRoom room = rooms.get(roomId);
        if (room == null || room.isExpired()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "NOT_FOUND", "message", "Pairing link expired or not found"))
                    .build();
        }
        String answer = body != null && body.path("answer").isTextual() ? body.path("answer").asText().trim() : "";
        if (answer.isEmpty() || answer.length() > MAX_TOKEN_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "INVALID_ANSWER", "message", "Valid answer token is required"))
                    .build();
        }
        room.answer = answer;
        return Response.ok(Map.of("ok", true))
                .header("Cache-Control", "no-store")
                .build();
    }

    @GET
    @Path("/rooms/{roomId}/answer")
    public Response pollAnswer(@PathParam("roomId") String roomId, @QueryParam("hostToken") String hostToken) {
        cleanupExpired();
        SignalRoom room = rooms.get(roomId);
        if (room == null || room.isExpired()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "NOT_FOUND", "message", "Pairing room expired or not found"))
                    .build();
        }
        if (hostToken == null || !room.hostToken.equals(hostToken)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "FORBIDDEN", "message", "Invalid hostToken"))
                    .build();
        }
        if (room.answer == null) {
            return Response.ok(Map.of("ready", false))
                    .header("Cache-Control", "no-store")
                    .build();
        }
        String ans = room.answer;
        rooms.remove(roomId);
        return Response.ok(Map.of("ready", true, "answer", ans))
                .header("Cache-Control", "no-store")
                .build();
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, SignalRoom>> it = rooms.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt <= now) {
                it.remove();
            }
        }
    }

    private static String randomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private static final class SignalRoom {
        final String offer;
        final String hostToken;
        final long expiresAt;
        volatile String answer;

        SignalRoom(String offer, String hostToken, long expiresAt) {
            this.offer = offer;
            this.hostToken = hostToken;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
