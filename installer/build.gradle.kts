plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "org.rxfilms.installer"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()

		applicationId = namespace
		versionName = project.getVersionName()
		versionCode = getVersionCode(versionName!!)
	}

	// Same release key as the TV app (keystore.* properties), so CI or a local release can sign both.
	signingConfigs {
		val keystoreFile = getProperty("keystore.file")
		val keystorePassword = getProperty("keystore.password")
		val signingKeyAlias = getProperty("signing.key.alias")
		val signingKeyPassword = getProperty("signing.key.password")

		if (keystoreFile != null && keystorePassword != null && signingKeyAlias != null && signingKeyPassword != null) {
			create("release") {
				storeFile = file(keystoreFile)
				storePassword = keystorePassword
				keyAlias = signingKeyAlias
				keyPassword = signingKeyPassword
			}
		}
	}

	buildTypes {
		release {
			signingConfig = signingConfigs.findByName("release")
		}
	}
}

dependencies {
	implementation(libs.dadb)
	implementation(libs.kotlinx.coroutines)
}
