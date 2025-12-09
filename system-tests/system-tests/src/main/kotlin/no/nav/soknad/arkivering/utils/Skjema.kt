package no.nav.soknad.arkivering.utils

	object Skjema {
		fun createSkjemaPathFromSkjemanr(skjemanr: String): String {
			val skjemanrWithoutNonAlphanumeric = removeNonAlphanumeric(skjemanr)

			return skjemanrWithoutNonAlphanumeric.trim().lowercase()
		}

		// Generates skjemanr in the format: NAV 10-99.99
		fun generateSkjemanr(): String {
			return "NAV ${(10..99).random()}-${(10..99).random()}.${(10..99).random()}"
		}

		// Generates vedleggsnr in the format: A1
		fun generateVedleggsnr(): String {
			return "${('A'..'Z').random()}${(1..9).random()}"
		}

		private fun removeNonAlphanumeric(input: String): String {
			return input.replace("[^a-zA-Z0-9]".toRegex(), "")
		}

	}
