pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        SCANNER_HOME = tool 'sonar-scanner'
        NEXUS_URL = 'http://your-nexus-server:8081'
        DOCKER_REGISTRY = 'your-docker-registry'
        K8S_NAMESPACE = 'webapps'
        K8S_CLUSTER_URL = 'https://172.31.8.146:6443'
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
        skipDefaultCheckout()
    }

    stages {
        stage('Clean Workspace') {
            steps {
                cleanWs()
                script {
                    currentBuild.description = "Initiated by ${currentBuild.getBuildCauses()[0].shortDescription}"
                }
            }
        }

        stage('Git Checkout') {
            steps {
                git(
                    branch: 'main',
                    credentialsId: 'git-cred',
                    url: 'https://github.com/prakashmk07/autodeploy-project.git',
                    poll: true,
                    changelog: true,
                    depth: 1
                )
                script {
                    def commit = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    env.GIT_COMMIT_SHORT = commit
                    currentBuild.displayName = "#${BUILD_NUMBER}-${commit}"
                }
            }
        }

        stage('Verify Repository') {
            steps {
                script {
                    def pomExists = fileExists 'pom.xml'
                    if (!pomExists) {
                        error "pom.xml not found in the root directory!"
                    }
                    sh 'ls -la'
                }
            }
        }

        stage('Dependency Check') {
            steps {
                sh 'mvn dependency:go-offline'
            }
        }

        stage('Compile') {
            steps {
                sh 'mvn clean compile'
            }
        }

        stage('Unit Tests') {
            steps {
                sh 'mvn test'
                junit 'target/surefire-reports/**/*.xml'
                archiveArtifacts artifacts: 'target/surefire-reports/**/*.*', allowEmptyArchive: true
            }
        }

        stage('Code Analysis') {
            steps {
                withSonarQubeEnv('sonar') {
                    sh """
                        $SCANNER_HOME/bin/sonar-scanner \
                        -Dsonar.projectName=BoardGame \
                        -Dsonar.projectKey=BoardGame \
                        -Dsonar.java.binaries=. \
                        -Dsonar.sourceEncoding=UTF-8 \
                        -Dsonar.host.url=${SONAR_HOST_URL} \
                        -Dsonar.login=${SONAR_AUTH_TOKEN}
                    """
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Security Scan') {
            steps {
                sh 'trivy fs --security-checks vuln,config --severity HIGH,CRITICAL --format table -o trivy-fs-report.html .'
                archiveArtifacts artifacts: 'trivy-fs-report.html', allowEmptyArchive: true
            }
        }

        stage('Build Package') {
            steps {
                sh 'mvn package -DskipTests'
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }

        stage('Publish to Nexus') {
            steps {
                withMaven(
                    maven: 'maven3',
                    mavenSettingsConfig: 'nexus-settings'
                ) {
                    sh 'mvn deploy -DskipTests'
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    docker.build("${DOCKER_REGISTRY}/boardshack:${env.GIT_COMMIT_SHORT}")
                }
            }
        }

        stage('Scan Docker Image') {
            steps {
                sh "trivy image --security-checks vuln --severity HIGH,CRITICAL --format table -o trivy-image-report.html ${DOCKER_REGISTRY}/boardshack:${env.GIT_COMMIT_SHORT}"
                archiveArtifacts artifacts: 'trivy-image-report.html', allowEmptyArchive: true
            }
        }

        stage('Push Docker Image') {
            steps {
                script {
                    docker.withRegistry("https://${DOCKER_REGISTRY}", 'docker-cred') {
                        docker.image("${DOCKER_REGISTRY}/boardshack:${env.GIT_COMMIT_SHORT}").push()
                        docker.image("${DOCKER_REGISTRY}/boardshack:${env.GIT_COMMIT_SHORT}").push('latest')
                    }
                }
            }
        }

        stage('Deploy to Kubernetes') {
            steps {
                withKubeConfig(
                    credentialsId: 'k8-cred',
                    namespace: env.K8S_NAMESPACE,
                    serverUrl: env.K8S_CLUSTER_URL
                ) {
                    sh "sed -i 's|IMAGE_TAG|${env.GIT_COMMIT_SHORT}|g' deployment-service.yaml"
                    sh 'kubectl apply -f deployment-service.yaml'
                    sh 'kubectl rollout status deployment/boardgame -n ${K8S_NAMESPACE} --timeout=300s'
                }
            }
        }

        stage('Smoke Test') {
            steps {
                script {
                    def appUrl = sh(returnStdout: true, script: 'kubectl get svc boardgame -n ${K8S_NAMESPACE} -o jsonpath="{.status.loadBalancer.ingress[0].ip}"').trim()
                    timeout(time: 2, unit: 'MINUTES') {
                        waitUntil {
                            try {
                                def response = httpRequest url: "http://${appUrl}:8080/health", validResponseCodes: '200'
                                return true
                            } catch (Exception e) {
                                sleep 10
                                return false
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                def duration = currentBuild.durationString.replace(' and counting', '')
                def success = currentBuild.resultIsBetterOrEqualTo('SUCCESS')
                def color = success ? 'good' : 'danger'
                def message = success ? "✅ Pipeline SUCCESS" : "❌ Pipeline FAILED"

                // Clean up workspace
                cleanWs()

                // Send notification
                emailext (
                    subject: "${env.JOB_NAME} - Build #${env.BUILD_NUMBER} - ${currentBuild.result}",
                    body: """
                        <html>
                        <body>
                        <h2>${env.JOB_NAME} - Build #${env.BUILD_NUMBER}</h2>
                        <p><strong>Status:</strong> ${currentBuild.result}</p>
                        <p><strong>Duration:</strong> ${duration}</p>
                        <p><strong>Commit:</strong> ${env.GIT_COMMIT_SHORT}</p>
                        <p><a href="${env.BUILD_URL}">View Build Details</a></p>
                        </body>
                        </html>
                    """,
                    to: 'prakashmurugaiya07@gmail.com',
                    attachLog: true,
                    compressLog: true
                )

                // Slack notification (optional)
                slackSend (
                    color: color,
                    message: "${message}: ${env.JOB_NAME} - Build #${env.BUILD_NUMBER} (${env.GIT_COMMIT_SHORT})",
                    channel: '#build-notifications'
                )
            }
        }
        failure {
            script {
                // Additional failure handling
                withKubeConfig(
                    credentialsId: 'k8-cred',
                    namespace: env.K8S_NAMESPACE,
                    serverUrl: env.K8S_CLUSTER_URL
                ) {
                    sh 'kubectl get events -n ${K8S_NAMESPACE} --sort-by=.metadata.creationTimestamp'
                }
            }
        }
        cleanup {
            // Final cleanup
            cleanWs()
        }
    }
}