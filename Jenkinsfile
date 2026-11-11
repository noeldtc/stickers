library identifier: 'android-pipeline@master', retriever: modernSCM(
    [$class: 'GitSCMSource', remote: 'https://github.com/noeldtc/JenkinsPipeline.git', credentialsId: 'GITHUB-PAT'])

// Pipeline unica para todas las apps: la logica vive en https://github.com/noeldtc/JenkinsPipeline (rama master).
// Sin opciones: detecta Gradle/Flutter y publica en Google Play si la carpeta Jenkins de la app tiene GOOGLE_PLAY_SERVICE_ACCOUNT,
// y en GitHub Releases si no.
androidPipeline()
