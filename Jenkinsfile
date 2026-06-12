pipeline {
    agent {
        label 'linux-docker'
    }

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        timeout(time: 120, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timestamps()
    }

    environment {
        IMAGE_REPOSITORY = ''
        RELEASE_IMAGE = 'false'
        RELEASE_VERSION = ''
        PUBLISH_IMAGE = 'false'
        UPDATE_LATEST_IMAGE = 'false'
    }

    stages {
        stage('检出代码') {
            steps {
                checkout scm
                sh 'git submodule sync --recursive'
                sh 'git submodule update --init --recursive'
                sh 'chmod +x ./mvnw'
            }
        }

        stage('检查构建环境') {
            steps {
                sh 'java -version'
                sh 'docker version'
                sh 'flutter --version'
            }
        }

        stage('计算发布版本') {
            steps {
                script {
                    String shortCommitHash = sh(
                        script: 'git rev-parse --short=7 HEAD',
                        returnStdout: true
                    ).trim()

                    String currentBranchName = env.BRANCH_NAME
                    if (currentBranchName == null || currentBranchName.trim().isEmpty()) {
                        currentBranchName = sh(
                            script: 'git rev-parse --abbrev-ref HEAD',
                            returnStdout: true
                        ).trim()
                    }

                    String currentTagName = env.TAG_NAME
                    if (currentTagName != null && !currentTagName.trim().isEmpty()) {
                        if (!(currentTagName ==~ /v[0-9]+\.[0-9]+\.[0-9]+/)) {
                            error("不支持的发布标签：${currentTagName}，必须使用 v1.2.3 格式")
                        }

                        env.RELEASE_VERSION = currentTagName.substring(1)
                        env.RELEASE_IMAGE = 'true'
                        env.UPDATE_LATEST_IMAGE = 'true'
                    } else if (currentBranchName == 'master' || currentBranchName == 'main') {
                        env.RELEASE_VERSION = "0.1.${env.BUILD_NUMBER}"
                        env.RELEASE_IMAGE = 'true'
                        env.UPDATE_LATEST_IMAGE = 'true'
                    } else {
                        env.RELEASE_VERSION = "0.1.${env.BUILD_NUMBER}-${shortCommitHash}"
                    }

                    String containerRegistry = env.CONTAINER_REGISTRY
                    String containerImageGroup = env.CONTAINER_IMAGE_GROUP
                    String containerImageName = env.CONTAINER_IMAGE_NAME
                    String registryCredentialsId = env.REGISTRY_CREDENTIALS_ID

                    if (containerRegistry == null) {
                        containerRegistry = ''
                    }
                    if (containerImageGroup == null || containerImageGroup.trim().isEmpty()) {
                        containerImageGroup = 'hhjmk'
                    }
                    if (containerImageName == null || containerImageName.trim().isEmpty()) {
                        containerImageName = 'windblog_quarkus'
                    }
                    if (registryCredentialsId == null) {
                        registryCredentialsId = ''
                    }

                    containerRegistry = containerRegistry.trim()
                    containerImageGroup = containerImageGroup.trim()
                    containerImageName = containerImageName.trim()
                    registryCredentialsId = registryCredentialsId.trim()

                    env.CONTAINER_REGISTRY = containerRegistry
                    env.CONTAINER_IMAGE_GROUP = containerImageGroup
                    env.CONTAINER_IMAGE_NAME = containerImageName
                    env.REGISTRY_CREDENTIALS_ID = registryCredentialsId
                    env.IMAGE_REPOSITORY = "${containerImageGroup}/${containerImageName}"

                    if (!containerRegistry.isEmpty()) {
                        env.IMAGE_REPOSITORY = "${containerRegistry}/${env.IMAGE_REPOSITORY}"
                    }

                    boolean hasContainerRegistry = !containerRegistry.isEmpty()
                    boolean hasRegistryCredentials = !registryCredentialsId.isEmpty()
                    boolean isReleaseImage = env.RELEASE_IMAGE == 'true'

                    if (isReleaseImage && hasContainerRegistry && hasRegistryCredentials) {
                        env.PUBLISH_IMAGE = 'true'
                    }

                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.RELEASE_VERSION}"
                    echo "分支：${currentBranchName}"
                    echo "版本：${env.RELEASE_VERSION}"
                    echo "镜像：${env.IMAGE_REPOSITORY}"
                    echo "是否推送镜像：${env.PUBLISH_IMAGE}"
                }
            }
        }

        stage('后端测试') {
            steps {
                sh './mvnw -B -ntp clean test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                }
            }
        }

        stage('管理端检查') {
            steps {
                dir('admin-flutter') {
                    sh 'flutter pub get'
                    sh 'flutter analyze'
                    sh 'flutter test'
                }
            }
        }

        stage('构建镜像变体') {
            when {
                expression {
                    return env.RELEASE_IMAGE == 'true'
                }
            }
            steps {
                sh '''
                    ./mvnw -B -ntp org.codehaus.mojo:versions-maven-plugin:2.18.0:set \
                        -DnewVersion="$RELEASE_VERSION" \
                        -DgenerateBackupPoms=false \
                        -DprocessAllModules=true

                    ./mvnw -B -ntp package \
                        -DskipTests \
                        -Dquarkus.application.version="$RELEASE_VERSION" \
                        -Dquarkus.container-image.build=false \
                        -Dquarkus.container-image.push=false

                    docker build \
                        -f src/main/docker/Dockerfile.jvm \
                        -t "$IMAGE_REPOSITORY:$RELEASE_VERSION-jvm" \
                        .

                    ./mvnw -B -ntp package \
                        -DskipTests \
                        -Dnative \
                        -Dquarkus.profile=edge \
                        -Dquarkus.application.version="$RELEASE_VERSION" \
                        -Dquarkus.native.container-build=true \
                        -Dquarkus.container-image.build=false \
                        -Dquarkus.container-image.push=false

                    docker build \
                        -f src/main/docker/Dockerfile.native-micro \
                        -t "$IMAGE_REPOSITORY:$RELEASE_VERSION" \
                        -t "$IMAGE_REPOSITORY:$RELEASE_VERSION-native-micro" \
                        .

                    docker build \
                        -f src/main/docker/Dockerfile.native \
                        -t "$IMAGE_REPOSITORY:$RELEASE_VERSION-native" \
                        .

                    if [ "$UPDATE_LATEST_IMAGE" = "true" ]; then
                        docker tag "$IMAGE_REPOSITORY:$RELEASE_VERSION" "$IMAGE_REPOSITORY:latest"
                        docker tag "$IMAGE_REPOSITORY:$RELEASE_VERSION-native-micro" "$IMAGE_REPOSITORY:latest-native-micro"
                        docker tag "$IMAGE_REPOSITORY:$RELEASE_VERSION-native" "$IMAGE_REPOSITORY:latest-native"
                        docker tag "$IMAGE_REPOSITORY:$RELEASE_VERSION-jvm" "$IMAGE_REPOSITORY:latest-jvm"
                    fi
                '''
            }
        }

        stage('推送镜像变体') {
            when {
                expression {
                    return env.PUBLISH_IMAGE == 'true'
                }
            }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: env.REGISTRY_CREDENTIALS_ID,
                        usernameVariable: 'REGISTRY_USERNAME',
                        passwordVariable: 'REGISTRY_PASSWORD'
                    )
                ]) {
                    sh '''
                        printf '%s' "$REGISTRY_PASSWORD" \
                            | docker login "$CONTAINER_REGISTRY" \
                                --username "$REGISTRY_USERNAME" \
                                --password-stdin

                        docker push "$IMAGE_REPOSITORY:$RELEASE_VERSION"
                        docker push "$IMAGE_REPOSITORY:$RELEASE_VERSION-native-micro"
                        docker push "$IMAGE_REPOSITORY:$RELEASE_VERSION-native"
                        docker push "$IMAGE_REPOSITORY:$RELEASE_VERSION-jvm"

                        if [ "$UPDATE_LATEST_IMAGE" = "true" ]; then
                            docker push "$IMAGE_REPOSITORY:latest"
                            docker push "$IMAGE_REPOSITORY:latest-native-micro"
                            docker push "$IMAGE_REPOSITORY:latest-native"
                            docker push "$IMAGE_REPOSITORY:latest-jvm"
                        fi
                    '''
                }
            }
        }
    }

    post {
        always {
            sh '''
                if [ -n "$CONTAINER_REGISTRY" ]; then
                    docker logout "$CONTAINER_REGISTRY" || true
                fi
            '''
        }
        success {
            echo "流水线完成，版本：${env.RELEASE_VERSION}"
        }
        failure {
            echo "流水线失败，请从首次失败的阶段开始检查。"
        }
    }
}
