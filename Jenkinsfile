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
        IMAGE_REPOSITORY = 'docker.io/biliwind/windblog'
        DOCKERHUB_CREDENTIALS_ID = 'dockerhub-credentials'
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
                        env.PUBLISH_IMAGE = 'true'
                        env.UPDATE_LATEST_IMAGE = 'true'
                    } else if (currentBranchName == 'master' || currentBranchName == 'main') {
                        env.RELEASE_VERSION = "0.1.${env.BUILD_NUMBER}"
                        env.PUBLISH_IMAGE = 'true'
                        env.UPDATE_LATEST_IMAGE = 'true'
                    } else {
                        env.RELEASE_VERSION = "0.1.${env.BUILD_NUMBER}-${shortCommitHash}"
                    }

                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.RELEASE_VERSION}"
                    echo "分支：${currentBranchName}"
                    echo "版本：${env.RELEASE_VERSION}"
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

        stage('构建 Native 镜像') {
            when {
                expression {
                    return env.PUBLISH_IMAGE == 'true'
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
                        -Dnative \
                        -Dquarkus.application.version="$RELEASE_VERSION" \
                        -Dquarkus.native.container-build=true \
                        -Dquarkus.container-image.tag="$RELEASE_VERSION" \
                        -Dquarkus.container-image.push=false
                '''
            }
        }

        stage('推送 Native 镜像') {
            when {
                expression {
                    return env.PUBLISH_IMAGE == 'true'
                }
            }
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: env.DOCKERHUB_CREDENTIALS_ID,
                        usernameVariable: 'DOCKERHUB_USERNAME',
                        passwordVariable: 'DOCKERHUB_TOKEN'
                    )
                ]) {
                    sh '''
                        printf '%s' "$DOCKERHUB_TOKEN" \
                            | docker login --username "$DOCKERHUB_USERNAME" --password-stdin

                        docker push "$IMAGE_REPOSITORY:$RELEASE_VERSION"

                        if [ "$UPDATE_LATEST_IMAGE" = "true" ]; then
                            docker tag \
                                "$IMAGE_REPOSITORY:$RELEASE_VERSION" \
                                "$IMAGE_REPOSITORY:latest"
                            docker push "$IMAGE_REPOSITORY:latest"
                        fi
                    '''
                }
            }
        }
    }

    post {
        always {
            sh 'docker logout docker.io || true'
        }
        success {
            echo "流水线完成，版本：${env.RELEASE_VERSION}"
        }
        failure {
            echo "流水线失败，请从首次失败的阶段开始检查。"
        }
    }
}
