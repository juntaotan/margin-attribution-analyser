pipeline {
    // The Jenkins agent must have Java 21 installed.
    // If a dedicated agent is labelled "jdk21", use:
    // agent { label 'jdk21' }
    agent any

    options {
        timestamps()
        disableConcurrentBuilds(abortPrevious: true)
        timeout(time: 20, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        skipDefaultCheckout(true)
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Environment') {
            steps {
                sh 'java -version'
                sh 'git --version'
            }
        }

        stage('Verify') {
            steps {
                dir('backend') {
                    sh '''
                        ./mvnw \
                          --batch-mode \
                          --no-transfer-progress \
                          clean verify
                    '''
                }
            }

            post {
                always {
                    junit(
                        testResults: 'backend/target/surefire-reports/*.xml',
                        allowEmptyResults: false
                    )
                }
            }
        }

        stage('Archive') {
            steps {
                archiveArtifacts(
                    artifacts: 'backend/target/*.jar',
                    excludes: 'backend/target/*.jar.original',
                    fingerprint: true,
                    onlyIfSuccessful: true
                )
            }
        }
    }

    post {
        success {
            echo 'CI passed: compilation, tests and packaging succeeded.'
        }

        failure {
            echo 'CI failed. Check the failed stage and JUnit report.'
        }
    }
}
