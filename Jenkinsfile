// Pipeline CI/CD – devops-gestion-projets (job Jenkins de type « Multibranch Pipeline »)
//
//   toutes les branches : GIT -> Build -> Tests + JaCoCo -> SonarQube -> Quality Gate -> Package
//   develop et main     : + Docker Build
//   main uniquement     : + Docker Push (Docker Hub) -> Deploy (docker compose)
//
// Aucun secret dans ce fichier. Credentials Jenkins attendus :
//   - dockerhub-creds      : Username with password (pseudo Docker Hub + ACCESS TOKEN, pas le mot de passe)
//   - mysql-root-password  : Secret text (mot de passe root de la base déployée)
//   - token SonarQube      : déjà relié au serveur « sonarqube » (Configurer le système)
// Prérequis VM : mysql-db (~/ma-stack) démarré pour les tests, utilisateur jenkins dans le groupe docker.
pipeline {
    agent any

    triggers {
        // GitHub ne peut pas joindre la VM (pas de webhook) : Jenkins vérifie les nouveaux commits toutes les 2 min
        pollSCM('H/2 * * * *')
    }

    environment {
        DOCKERHUB = credentials('dockerhub-creds')   // fournit DOCKERHUB_USR et DOCKERHUB_PSW
        TAG = "${env.BUILD_NUMBER}"
    }

    stages {
        stage('GIT') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                dir('backend') {
                    sh 'chmod +x mvnw && ./mvnw -B clean compile'
                }
            }
        }

        stage('Tests + JaCoCo') {
            steps {
                dir('backend') {
                    // la phase test génère aussi le rapport JaCoCo (target/site/jacoco/)
                    sh './mvnw -B test'
                }
            }
            post {
                always {
                    junit 'backend/target/surefire-reports/*.xml'
                    archiveArtifacts artifacts: 'backend/target/site/jacoco/**', allowEmptyArchive: true
                }
            }
        }

        stage('SonarQube') {
            steps {
                dir('backend') {
                    withSonarQubeEnv('sonarqube') {
                        // SonarQube lit automatiquement target/site/jacoco/jacoco.xml -> couverture
                        sh './mvnw -B org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dsonar.projectKey=DevOps-AppGestionDesProjets -Dsonar.projectName=DevOps-AppGestionDesProjets'
                    }
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

        stage('Package') {
            steps {
                dir('backend') {
                    sh './mvnw -B package -DskipTests'
                }
            }
        }

        stage('Docker Build') {
            when { anyOf { branch 'main'; branch 'develop' } }
            steps {
                sh '''
                    docker build -t $DOCKERHUB_USR/gp-backend:$TAG -t $DOCKERHUB_USR/gp-backend:latest backend
                    docker build -t $DOCKERHUB_USR/gp-frontend:$TAG -t $DOCKERHUB_USR/gp-frontend:latest frontend
                '''
            }
        }

        stage('Docker Push') {
            when { branch 'main' }
            steps {
                sh '''
                    echo "$DOCKERHUB_PSW" | docker login -u "$DOCKERHUB_USR" --password-stdin
                    docker push $DOCKERHUB_USR/gp-backend:$TAG
                    docker push $DOCKERHUB_USR/gp-backend:latest
                    docker push $DOCKERHUB_USR/gp-frontend:$TAG
                    docker push $DOCKERHUB_USR/gp-frontend:latest
                '''
            }
            post {
                always {
                    sh 'docker logout || true'
                }
            }
        }

        stage('Deploy') {
            when { branch 'main' }
            steps {
                withCredentials([string(credentialsId: 'mysql-root-password', variable: 'MYSQL_ROOT_PASSWORD')]) {
                    // -p fixe le nom du projet Compose : chaque déploiement remplace le précédent
                    sh '''
                        export DOCKERHUB_USER=$DOCKERHUB_USR
                        docker compose -p gestion-projets up -d --no-build
                        docker compose -p gestion-projets ps
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "Pipeline OK sur ${env.BRANCH_NAME} (build ${env.BUILD_NUMBER})"
        }
        failure {
            echo "Pipeline en échec sur ${env.BRANCH_NAME} : voir le stage en rouge"
        }
    }
}
