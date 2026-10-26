package org.jellyfin.androidtv.ui.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppUpdaterTests : FunSpec({
	test("newer patch, minor and major versions are updates") {
		isNewerVersion("1.0.1", "1.0.0") shouldBe true
		isNewerVersion("1.1.0", "1.0.9") shouldBe true
		isNewerVersion("2.0.0", "1.9.9") shouldBe true
	}

	test("same or older versions are not updates") {
		isNewerVersion("1.0.0", "1.0.0") shouldBe false
		isNewerVersion("1.0.0", "1.0.1") shouldBe false
		isNewerVersion("1.10.0", "1.9.0") shouldBe true
	}

	test("pre-release suffix on the installed build is ignored") {
		isNewerVersion("1.0.0", "0.0.0-dev.1") shouldBe true
	}
})
