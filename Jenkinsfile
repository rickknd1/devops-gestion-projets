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
//    9. Deploy to Nexus     publication du jar dans Nexus (version 1.0.<build>) – main uniquement
//   10. Docker Build        images backend + frontend construites en parallèle
//
//  Déploiement continu (main uniquement)
//   11. Docker Push         publication des images sur Docker Hub
//   12. Deploy Compose      lancement des 3 conteneurs (mysql, backend, frontend) sur la VM
//   13. Deploy Backend      Spring sur Kubernetes (Minikube) : rolling update + rollback si échec
//   14. Deploy Frontend     Angular/nginx sur Kubernetes (Minikube)
//   15. Smoke Tests         vérification que l'API et le front répondent (Compose et Kubernetes)
//
//  Aucun secret dans ce fichier. Credentials Jenkins attendus :
//    - dockerhub_rickknd    Username with password (pseudo Docker Hub + ACCESS TOKEN)
//    - mysql-root-password  Secret text (mot de passe root de la base déployée)
//    - nexus-credentials    Username with password (compte Nexus autorisé à publier)
//    - token SonarQube      déjà relié au serveur « sonarqube » (Configurer le système)
//  Prérequis VM : (les tests utilisent une base H2 en mémoire, aucun MySQL requis)
//                 utilisateur jenkins dans le groupe docker ;
//                 kubeconfig de Minikube dans /var/lib/jenkins/.kube/config (Minikube, driver docker) ;
//                 Secret spring-secret et MySQL déjà présents dans le namespace devops (atelier 8).
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
        NEXUS_URL      = 'http://localhost:8081'
        NEXUS_CREDS    = 'nexus-credentials'
        K8S_NAMESPACE  = 'devops'
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
        stage('Deploy to Nexus') {
            when { branch 'main' }
            steps {
                echo "==> Publication du jar dans Nexus (maven-releases, version 1.0.${TAG})"
                // une version par build : un dépôt « releases » refuse de republier une version existante,
                // et 1.0.<build> relie l'artefact Nexus au tag Docker du même build
                dir('backend') {
                    withCredentials([usernamePassword(credentialsId: env.NEXUS_CREDS,
                            usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASSWORD')]) {
                        sh '''
                            ./mvnw -B -s ../ci/maven-settings.xml deploy:deploy-file \
                              -DgroupId=tn.esprit -DartifactId=backend -Dversion=1.0.$TAG \
                              -Dpackaging=jar -Dfile=$(ls target/backend-*.jar) -DgeneratePom=true \
                              -DrepositoryId=nexus -Durl=$NEXUS_URL/repository/maven-releases/
                        '''
                    }
                }
                echo "Artefact : ${env.NEXUS_URL}/#browse/browse:maven-releases:tn%2Fesprit%2Fbackend"
            }
        }

        // ---------------------------------------------------------------- 10
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

        // ---------------------------------------------------------------- 11
        stage('Docker Push') {
            when { branch 'main' }
            steps {
                echo '==> Publication des images sur Docker Hub (tag du build + latest)'
                withCredentials([usernamePassword(credentialsId: env.DOCKERHUB_CREDS,
                        usernameVariable: 'DH_USER', passwordVariable: 'DH_TOKEN')]) {
                    // le réseau de la VM coupe parfois quelques secondes : 3 essais avant d'abandonner
                    retry(3) {
                        sh '''
                            echo "$DH_TOKEN" | docker login -u "$DH_USER" --password-stdin
                            docker push $BACKEND_IMAGE:$TAG
                            docker push $BACKEND_IMAGE:latest
                            docker push $FRONTEND_IMAGE:$TAG
                            docker push $FRONTEND_IMAGE:latest
                        '''
                    }
                }
                echo "Images visibles sur https://hub.docker.com/u/${env.DOCKERHUB_USER}"
            }
            post {
                always {
                    sh 'docker logout || true'
                }
            }
        }

        // ---------------------------------------------------------------- 12
        stage('Deploy Compose') {
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

        // ---------------------------------------------------------------- 13
        stage('Deploy Backend') {
            when { branch 'main' }
            steps {
                echo "==> Déploiement du backend sur Kubernetes : ${BACKEND_IMAGE}:${TAG}"
                // approche déclarative : le manifeste versionné dans Git est appliqué avec l'image du build
                sh '''
                    kubectl get namespace $K8S_NAMESPACE
                    kubectl get secret spring-secret -n $K8S_NAMESPACE > /dev/null
                    # L'image vient d'être construite sur cette VM : on la transmet directement à Minikube
                    # (quelques secondes) au lieu de la retélécharger depuis Docker Hub, trop lent ici
                    docker save --platform linux/amd64 $BACKEND_IMAGE:$TAG | docker exec -i minikube ctr -n k8s.io images import -
                    sed "s#image: .*gp-backend.*#image: $BACKEND_IMAGE:$TAG#" k8s/backend.yaml | kubectl apply -f -
                    kubectl rollout status deployment/spring -n $K8S_NAMESPACE --timeout=420s
                    kubectl get pods -n $K8S_NAMESPACE -l app=spring -o wide
                '''
            }
            post {
                failure {
                    // l'ancienne version reprend la main : l'application reste disponible
                    sh 'kubectl rollout undo deployment/spring -n $K8S_NAMESPACE || true'
                }
            }
        }

        // ---------------------------------------------------------------- 14
        stage('Deploy Frontend') {
            when { branch 'main' }
            steps {
                echo "==> Déploiement du frontend sur Kubernetes : ${FRONTEND_IMAGE}:${TAG}"
                sh '''
                    docker save --platform linux/amd64 $FRONTEND_IMAGE:$TAG | docker exec -i minikube ctr -n k8s.io images import -
                    sed "s#image: .*gp-frontend.*#image: $FRONTEND_IMAGE:$TAG#" k8s/frontend.yaml | kubectl apply -f -
                    kubectl rollout status deployment/frontend -n $K8S_NAMESPACE --timeout=300s
                    kubectl get pods,svc -n $K8S_NAMESPACE
                '''
            }
            post {
                failure {
                    sh 'kubectl rollout undo deployment/frontend -n $K8S_NAMESPACE || true'
                }
            }
        }

        // ---------------------------------------------------------------- 15
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

                    echo "===== Kubernetes ====="
                    K8S_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}')
                    echo "--- API  : GET /entreprise/all (NodePort 30081)"
                    curl -sf http://$K8S_IP:30081/entreprise/all
                    echo
                    echo "--- Front : page d'accueil (NodePort 30080)"
                    curl -sf -o /dev/null -w "HTTP %{http_code}\\n" http://$K8S_IP:30080/
                    echo "--- Front -> API : nginx -> Service backend -> pods Spring"
                    curl -sf -o /dev/null -w "HTTP %{http_code}\\n" http://$K8S_IP:30080/api/entreprise/all
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
             ${env.BRANCH_NAME == 'main' ? 'Compose : http://<IP-VM>:4200 (API :8089)   Kubernetes : NodePort 30080 (API :30081)' : 'Intégration validée (pas de déploiement sur cette branche)'}
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
