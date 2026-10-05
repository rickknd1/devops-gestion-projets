// =====================================================================================
//  Pipeline CI/CD – devops-gestion-projets   (job Jenkins « Multibranch Pipeline »)
// =====================================================================================
//
//  Intégration continue (toutes les branches)
//    1. Checkout            récupération du code source depuis GitHub
//    2. Infos build         contexte : branche, commit, auteur, message
//    3. Vérif. outils       versions Java, Maven, Docker, Compose
//    4. Build               compilation du backend Spring Boot
//    5. Tests + JaCoCo      tests unitaires + rapport de couverture
//    6. SonarQube           analyse statique (bugs, vulnérabilités, code smells, couverture)
//    7. Quality Gate        barrière qualité : le pipeline s'arrête si elle échoue
//    8. Package             création du jar exécutable, archivé dans Jenkins
//
//  Livraison continue (develop et main)
//    9. Docker Build        images backend + frontend construites en parallèle
//
//  Déploiement continu (main uniquement)
//   10. Docker Push         publication des images sur Docker Hub
//   11. Deploy              lancement des 3 conteneurs (mysql, backend, frontend)
//   12. Smoke Tests         vérification que l'API et le front répondent
//
//  Aucun secret dans ce fichier. Credentials Jenkins attendus :
//    - dockerhub_rickknd    Username with password (pseudo Docker Hub + ACCESS TOKEN)
//    - mysql-root-password  Secret text (mot de passe root de la base déployée)
//    - token SonarQube      déjà relié au serveur « sonarqube » (Configurer le système)
//  Prérequis VM : (les tests utilisent une base H2 en mémoire, aucun MySQL requis)
//                 utilisateur jenkins dans le groupe docker.
// =====================================================================================
pipeline {
    agent any

    options {
        timestamps()                                    // heure devant chaque ligne de log
        timeout(time: 45, unit: 'MINUTES')              // garde-fou : un build bloqué est coupé
        buildDiscarder(logRotator(numToKeepStr: '10'))  // ne garde que les 10 derniers builds
        disableConcurrentBuilds()                       // un seul build à la fois par branche (RAM de la VM)
        skipDefaultCheckout(true)                       // le checkout est fait dans son propre stage
    }

    triggers {
        // GitHub ne peut pas joindre la VM (pas de webhook) : Jenkins vérifie les nouveaux commits toutes les 2 min
        pollSCM('H/2 * * * *')
    }

    environment {
        // Le credential Docker Hub n'est lu QUE dans les stages Docker : les branches feature/* n'en dépendent pas
        DOCKERHUB_CREDS = 'dockerhub_rickknd'
        TAG            = "${env.BUILD_NUMBER}"
        SONAR_KEY      = 'DevOps-AppGestionDesProjets'
        COMPOSE_PROJECT = 'gestion-projets'
        APP_HOST       = 'localhost'
    }

    stages {

        // ---------------------------------------------------------------- 1
        stage('Checkout') {
            steps {
                echo '==> Récupération du code source depuis GitHub'
                checkout scm
                script {
                    env.GIT_SHORT   = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                    env.GIT_AUTHOR  = sh(script: 'git log -1 --format=%an', returnStdout: true).trim()
                    env.GIT_MESSAGE = sh(script: 'git log -1 --format=%s', returnStdout: true).trim()
                    currentBuild.description = "${env.BRANCH_NAME} @ ${env.GIT_SHORT}"
                }
            }
        }

        // ---------------------------------------------------------------- 2
        stage('Infos build') {
            steps {
                echo """
                ============================================================
                 Build        : #${env.BUILD_NUMBER}
                 Branche      : ${env.BRANCH_NAME}
                 Commit       : ${env.GIT_SHORT}
                 Auteur       : ${env.GIT_AUTHOR}
                 Message      : ${env.GIT_MESSAGE}
                 Images       : <compte Docker Hub>/gp-backend:${env.TAG} et gp-frontend:${env.TAG}
                 Livraison    : ${env.BRANCH_NAME == 'main' ? 'OUI (push Docker Hub + déploiement)' : 'NON (intégration seulement)'}
                ============================================================
                """
            }
        }

        // ---------------------------------------------------------------- 3
        stage('Vérification des outils') {
            steps {
                echo '==> Versions des outils utilisés par le pipeline'
                dir('backend') {
                    sh '''
                        chmod +x mvnw
                        java -version
                        ./mvnw -v
                        docker --version
                        docker compose version
                    '''
                }
            }
        }

        // ---------------------------------------------------------------- 4
        stage('Build') {
            steps {
                echo '==> Compilation du backend Spring Boot (Maven Wrapper)'
                dir('backend') {
                    sh './mvnw -B clean compile'
                }
            }
        }

        // ---------------------------------------------------------------- 5
        stage('Tests + JaCoCo') {
            steps {
                echo '==> Tests unitaires (Mockito) + test de démarrage Spring (base H2 en mémoire), couverture JaCoCo'
                dir('backend') {
                    sh './mvnw -B test'
                    sh '''
                        echo "Rapport de couverture JaCoCo :"
                        ls -la target/site/jacoco/
                    '''
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'backend/target/surefire-reports/*.xml'
                    archiveArtifacts artifacts: 'backend/target/site/jacoco/**', allowEmptyArchive: true
                }
            }
        }

        // ---------------------------------------------------------------- 6
        stage('SonarQube') {
            steps {
                echo '==> Analyse statique envoyée à SonarQube (avec le rapport de couverture JaCoCo)'
                dir('backend') {
                    withSonarQubeEnv('sonarqube') {
                        sh """
                            ./mvnw -B org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
                              -Dsonar.projectKey=${SONAR_KEY} \
                              -Dsonar.projectName=${SONAR_KEY} \
                              -Dsonar.projectVersion=${BUILD_NUMBER} \
                              -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
                        """
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 7
        stage('Quality Gate') {
            steps {
                echo '==> Attente du verdict SonarQube (webhook) : le pipeline s\'arrête si la barrière échoue'
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        // ---------------------------------------------------------------- 8
        stage('Package') {
            steps {
                echo '==> Création du jar exécutable'
                dir('backend') {
                    sh './mvnw -B package -DskipTests'
                    sh 'ls -lh target/*.jar'
                }
            }
            post {
                success {
                    archiveArtifacts artifacts: 'backend/target/*.jar', fingerprint: true
                }
            }
        }

        // ---------------------------------------------------------------- 9
        stage('Compte Docker Hub') {
            when { anyOf { branch 'main'; branch 'develop' } }
            steps {
                echo '==> Lecture du compte Docker Hub (credential Jenkins dockerhub_rickknd)'
                // seul le pseudo (non secret) est gardé ; le token reste dans le credential
                withCredentials([usernamePassword(credentialsId: env.DOCKERHUB_CREDS,
                        usernameVariable: 'DH_USER', passwordVariable: 'DH_TOKEN')]) {
                    script {
                        env.DOCKERHUB_USER = env.DH_USER
                        env.BACKEND_IMAGE  = "${env.DH_USER}/gp-backend"
                        env.FRONTEND_IMAGE = "${env.DH_USER}/gp-frontend"
                    }
                }
                echo "Images : ${env.BACKEND_IMAGE}:${env.TAG} et ${env.FRONTEND_IMAGE}:${env.TAG}"
            }
        }

        stage('Docker Build') {
            when { anyOf { branch 'main'; branch 'develop' } }
            parallel {
                stage('Image backend') {
                    steps {
                        echo "==> Construction de ${BACKEND_IMAGE}:${TAG}"
                        sh """
                            docker build \
                              --label git-commit=${GIT_SHORT} \
                              --label build=${BUILD_NUMBER} \
                              -t ${BACKEND_IMAGE}:${TAG} -t ${BACKEND_IMAGE}:latest backend
                        """
                    }
                }
                stage('Image frontend') {
                    steps {
                        echo "==> Construction de ${FRONTEND_IMAGE}:${TAG} (build Angular + nginx)"
                        sh """
                            docker build \
                              --label git-commit=${GIT_SHORT} \
                              --label build=${BUILD_NUMBER} \
                              -t ${FRONTEND_IMAGE}:${TAG} -t ${FRONTEND_IMAGE}:latest frontend
                        """
                    }
                }
            }
            post {
                success {
                    sh "docker images | grep -E 'gp-backend|gp-frontend' | head -6"
                }
            }
        }

        // ---------------------------------------------------------------- 10
        stage('Docker Push') {
            when { branch 'main' }
            steps {
                echo '==> Publication des images sur Docker Hub (tag du build + latest)'
                withCredentials([usernamePassword(credentialsId: env.DOCKERHUB_CREDS,
                        usernameVariable: 'DH_USER', passwordVariable: 'DH_TOKEN')]) {
                    sh '''
                        echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin
                        docker push $BACKEND_IMAGE:$TAG
                        docker push $BACKEND_IMAGE:latest
                        docker push $FRONTEND_IMAGE:$TAG
                        docker push $FRONTEND_IMAGE:latest
                    '''
                }
                echo "Images visibles sur https://hub.docker.com/u/${env.DOCKERHUB_USER}"
            }
            post {
                always {
                    sh 'docker logout || true'
                }
            }
        }

        // ---------------------------------------------------------------- 11
        stage('Deploy') {
            when { branch 'main' }
            steps {
                echo '==> Déploiement : 3 conteneurs (mysql, backend, frontend) via Docker Compose'
                withCredentials([string(credentialsId: 'mysql-root-password', variable: 'MYSQL_ROOT_PASSWORD')]) {
                    // -p fixe le nom du projet Compose : chaque déploiement remplace le précédent
                    sh '''
                        docker compose -p $COMPOSE_PROJECT up -d --no-build
                        docker compose -p $COMPOSE_PROJECT ps
                    '''
                }
            }
        }

        // ---------------------------------------------------------------- 12
        stage('Smoke Tests') {
            when { branch 'main' }
            steps {
                echo '==> Vérification que l\'application déployée répond'
                sh '''
                    echo "Attente du démarrage du backend (jusqu'à 2 min)..."
                    for i in $(seq 1 24); do
                        if curl -sf http://$APP_HOST:8089/entreprise/all > /dev/null; then
                            echo "Backend OK après $((i * 5)) s"
                            break
                        fi
                        sleep 5
                    done
                    echo "--- API  : GET /entreprise/all"
                    curl -sf http://$APP_HOST:8089/entreprise/all
                    echo
                    echo "--- Front : page d'accueil (via nginx)"
                    curl -sf -o /dev/null -w "HTTP %{http_code}\\n" http://$APP_HOST:4200/
                    echo "--- Front -> API : proxy /api"
                    curl -sf -o /dev/null -w "HTTP %{http_code}\\n" http://$APP_HOST:4200/api/entreprise/all
                '''
            }
        }
    }

    post {
        success {
            echo """
            ============================================================
             SUCCÈS – ${env.BRANCH_NAME} @ ${env.GIT_SHORT} (build #${env.BUILD_NUMBER})
             Durée : ${currentBuild.durationString}
             ${env.BRANCH_NAME == 'main' ? 'Application : http://<IP-VM>:4200   API : http://<IP-VM>:8089' : 'Intégration validée (pas de déploiement sur cette branche)'}
            ============================================================
            """
        }
        failure {
            echo "ÉCHEC – ${env.BRANCH_NAME} @ ${env.GIT_SHORT} : voir le stage en rouge et sa Console Output"
            script {
                // le nettoyage ne doit jamais masquer l'erreur d'origine
                try {
                    if (env.BRANCH_NAME == 'main') {
                        sh "docker compose -p ${env.COMPOSE_PROJECT} logs --tail 30 backend || true"
                    }
                } catch (err) {
                    echo "Logs du backend indisponibles : ${err.message}"
                }
            }
        }
        always {
            script {
                // supprime les images intermédiaires inutiles (libère du disque dans la VM)
                try {
                    sh 'docker image prune -f || true'
                } catch (err) {
                    echo "Nettoyage Docker ignoré : ${err.message}"
                }
            }
        }
    }
}
