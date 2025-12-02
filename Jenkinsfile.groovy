pipeline {
    agent { label 'supportAgent'}
    
    // Define environment variables
    environment {
        // --- REPLACE THESE VALUES ---
        EC2_IP = 'YOUR_APPLICATION_EC2_PUBLIC_IP' 
        EC2_USER = 'ubuntu' 
        SSH_CREDENTIAL_ID = 'aws-deploy-ssh-key' 
        // -----------------------------
        
        APP_DIR = '/var/www/chat-app'
        BACKEND_DIR = "${APP_DIR}/backend"
        BACKEND_REPO = 'https://github.com/D-Godhani/real-time-chat-app-backend'
        BACKEND_SERVICE_NAME = 'chat-api'
    }

    stages {
        stage('Checkout Backend Code') {
            steps {
                echo 'Checking out Backend code (skipping cleanWs() for cache speed)...'
                git url: env.BACKEND_REPO, branch: 'main'
            }
        }

        stage('Build & Dependencies') {
            steps {
                echo 'Installing pnpm and dependencies on agent...'
                // Install pnpm globally on the Agent (Requires NOPASSWD fix on Agent)
                sh 'sudo npm install -g pnpm' 
                sh 'pnpm install' 
            }
        }

        stage('Deploy Backend & DB Setup') {
            steps {
                echo "Deploying Backend to EC2 instance: ${EC2_IP}"
                sshagent(credentials: [SSH_CREDENTIAL_ID]) {
                    
                    // --- 1. Prepare Target Directory and Permissions & Install DB ---
                    sh """
                        ssh -o StrictHostKeyChecking=no ${EC2_USER}@${EC2_IP} <<EOF
                            echo "Preparing backend directory and services..."
                            # Setup directories, ownership, and stop service
                            sudo mkdir -p ${BACKEND_DIR}
                            sudo chown -R ${EC2_USER}:${EC2_USER} ${APP_DIR}
                            pm2 delete ${BACKEND_SERVICE_NAME} || true
                            
                            # --- MongoDB Installation (Runs only if needed) ---
                            echo "Installing and starting MongoDB..."
                            sudo tee /etc/yum.repos.d/mongodb-org-7.0.repo<<EOM
[mongodb-org-7.0]
name=MongoDB Repository
baseurl=https://repo.mongodb.org/yum/amazon/2023/mongodb-org/7.0/x86_64/
gpgcheck=1
enabled=1
gpgkey=https://www.mongodb.org/static/pgp/server-7.0.asc
EOM
                            sudo dnf install -y mongodb-org
                            sudo systemctl start mongod 
                            sudo systemctl enable mongod
                            
                        EOF
                    """
                    
                    // --- 2. Copy Files and Start Service ---
                    echo 'Transferring backend files and restarting API service...'
                    sh """
                        scp -r * ${EC2_USER}@${EC2_IP}:${BACKEND_DIR}/
                        
                        ssh ${EC2_USER}@${EC2_IP} "
                            cd ${BACKEND_DIR}
                            
                            # --- Environment File Setup ---
                            echo \"DATABASE_URL=mongodb://localhost:27017/chatapp\" > .env
                            echo \"JWT_SECRET=your_jwt_secret\" >> .env
                            
                            # Install PM2 and local dependencies (Requires NOPASSWD on target EC2)
                            sudo npm install -g pm2 pnpm
                            pnpm install --production

                            # --- Start Backend Service with PM2 ---
                            /usr/bin/pm2 start pnpm --name ${BACKEND_SERVICE_NAME} --interpreter bash -- start || /usr/bin/pm2 restart ${BACKEND_SERVICE_NAME}
                            /usr/bin/pm2 save
                        "
                    """
                }
            }
        }
    }
}
