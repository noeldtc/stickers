// La lógica vive en https://github.com/noeldtc/JenkinsPipeline (rama master): se actualiza sola en cada build.
library identifier: 'android-pipeline@master', retriever: modernSCM(
    [$class: 'GitSCMSource', remote: 'https://github.com/noeldtc/JenkinsPipeline.git', credentialsId: 'GITHUB-PAT'])

// Stickers es Flutter: ci/with-flutter.sh instala (una sola vez, en $JENKINS_HOME/tools) y activa el SDK fijado.
// Firma con las credenciales globales SIGNING_* / KEYSTORE_BASE64; la rama de release es main.
androidPipeline(
    appName: 'Stickers',
    // Stickers publica en GitHub Releases aunque GOOGLE_PLAY_SERVICE_ACCOUNT exista como credencial global
    playCredentialId: 'NO_PLAY',
    releaseBranches: ['main'],
    tagPrefix: 'jenkins-',
    // El build.gradle vive en android/app; Gradle lee la ruta absoluta desde SIGNING_STORE_FILE
    signing: [keystorePath: 'android/app/keystore.jks'],
    // versionCode = nº de commits (siempre creciente); el versionName sale del pubspec (ci/build-release.sh)
    versioning: true,
    // No hay tests en el repo: solo análisis estático (errores) y APK debug
    prCommand: 'bash ci/with-flutter.sh bash ci/check.sh',
    prTestResults: 'build/test-results/**/*.xml',
    prArchive: 'build/app/outputs/flutter-apk/app-debug.apk',
    releaseCommand: { sh 'bash ci/with-flutter.sh bash ci/build-release.sh' },
    apkGlob: 'build/app/outputs/flutter-apk/app-release.apk',
)
