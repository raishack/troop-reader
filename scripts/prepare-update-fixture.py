#!/usr/bin/env python3
"""Generate synthetic updater APK using this machine's default debug signing key."""
from pathlib import Path
import os
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
project = root / 'build' / 'update-fixture-project'
(project / 'app' / 'src' / 'main').mkdir(parents=True, exist_ok=True)
(project / 'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "UpdateFixture"
include(":app")
''')
(project / 'build.gradle.kts').write_text('plugins { id("com.android.application") version "8.9.1" apply false }\n')
(project / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx1024m\n')
(project / 'app' / 'build.gradle.kts').write_text('''plugins { id("com.android.application") }
android {
    namespace = "es.gamingtroop.reader"
    compileSdk = 35
    defaultConfig {
        applicationId = "es.gamingtroop.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 100000
        versionName = "0.1.0-fixture100000"
    }
}
''')
(project / 'app' / 'src' / 'main' / 'AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:hasCode="false" android:label="Updater test fixture" /></manifest>''')
if (root / 'local.properties').exists():
    shutil.copy2(root / 'local.properties', project / 'local.properties')
wrapper = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
subprocess.run([str(wrapper), '-p', str(project), 'assembleDebug', '--no-daemon'], check=True)
target = root / 'app' / 'src' / 'androidTest' / 'assets' / 'update-fixture.apk'
target.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(project / 'app' / 'build' / 'outputs' / 'apk' / 'debug' / 'app-debug.apk', target)
print('Generated synthetic test APK; never install on a personal device.')
