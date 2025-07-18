pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        SCANNER_HOME = tool 'sonar-scanner'
    }

    stages {
        stage('Git Checkout') {
            steps {
                git branch: 'main', credentialsId: 'git-cred', url: 'https://github.com/prakashmk07/autodeploy-project.git'
            }
        }

        stage('Generate pom.xml') {
            steps {
                writeFile file: 'pom.xml', text: '''
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>boardshack</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <build>
        <sourceDirectory>src</sourceDirectory>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.8.1</version>
                <configuration>
                    <source>17</source>
                    <target>17</target>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
'''
            }
        }

        stage('Compile') {
            steps {
                sh 'mvn compile'
            }
        }

        stage('Test') {
            steps {
                sh 'mvn test'
            }
        }

        stage('File System Scan') {
            steps {
                sh 'trivy fs --format table -o trivy-fs-report.html .'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('sonar') {
                    withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
    sh '''
        $SCANNER_HOME/bin/sonar-scanner \
        -Dsonar.projectName=BoardGame \
        -Dsonar.projectKey=BoardGame \
        -Dsonar.java.binaries=. \
        -Dsonar.login=$SONAR_TOKEN
    '''
}
                }
            }
        }

        stage('Quality Gate') {
            steps {
                script {
                    waitForQualityGate abortPipeline: false, credentialsId: 'sonar-token'
                }
            }
        }

        stage('Build') {
            steps {
                sh 'mvn package'
            }
        }

        stage('Publish To Nexus') {
            steps {
                withMaven(globalMavenSettingsConfig: 'global-settings', jdk: 'jdk17', maven: 'maven3') {
                    sh 'mvn deploy'
                }
            }
        }

        stage('Build & Tag Docker Image') {
            steps {
                script {
                    withDockerRegistry(credentialsId: 'docker-cred', toolName: 'docker') {
                        sh 'docker build -t pdeveopsh/boardshack:latest .'
                    }
                }
            }
        }

        stage('Docker Image Scan') {
            steps {
                sh 'trivy image --format table -o trivy-image-report.html pdeveopsh/boardshack:latest'
            }
        }

        stage('Push Docker Image') {
            steps {
                script {
                    withDockerRegistry(credentialsId: 'docker-cred', toolName: 'docker') {
                        sh 'docker push pdeveopsh/boardshack:latest'
                    }
                }
            }
        }

        stage('Deploy To Kubernetes') {
            steps {
                withKubeConfig(credentialsId: 'k8-cred', namespace: 'webapps', serverUrl: 'https://172.31.8.146:6443') {
                    sh 'kubectl apply -f deployment-service.yaml'
                }
            }
        }

        stage('Verify the Deployment') {
            steps {
                withKubeConfig(credentialsId: 'k8-cred', namespace: 'webapps', serverUrl: 'https://172.31.8.146:6443') {
                    sh 'kubectl get pods -n webapps'
                    sh 'kubectl get svc -n webapps'
                }
            }
        }
    }

    post {
        always {
            script {
                def jobName = env.JOB_NAME
                def buildNumber = env.BUILD_NUMBER
                def pipelineStatus = currentBuild.result ?: 'UNKNOWN'
                def bannerColor = pipelineStatus.toUpperCase() == 'SUCCESS' ? 'green' : 'red'

                def body = """
<html>
<body>
<div style="border: 4px solid ${bannerColor}; padding: 10px;">
    <h2>${jobName} - Build ${buildNumber}</h2>
    <div style="background-color: ${bannerColor}; padding: 10px;">
        <h3 style="color: white;">Pipeline Status: ${pipelineStatus.toUpperCase()}</h3>
    </div>
    <p>Check the <a href="${BUILD_URL}">console output</a>.</p>
</div>
</body>
</html>
"""

                emailext (
                    subject: "${jobName} - Build ${buildNumber} - ${pipelineStatus.toUpperCase()}",
                    body: body,
                    to: 'prakashmurugaiya07@gmail.com',
                    from: 'jenkins@example.com',
                    replyTo: 'jenkins@example.com',
                    mimeType: 'text/html',
                    attachmentsPattern: 'trivy-image-report.html'
                )
            }
        }
    }
}
