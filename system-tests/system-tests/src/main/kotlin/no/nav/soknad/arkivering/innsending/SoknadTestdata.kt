package no.nav.soknad.arkivering.innsending

import no.nav.soknad.arkivering.innsending.api.SendinnFilApi
import no.nav.soknad.arkivering.innsending.model.DokumentSoknadDto
import java.io.File

class SoknadTestdata(
	private val soknad: DokumentSoknadDto,
	private val sendinnFilApi: SendinnFilApi,
) {

	val innsendingsId = soknad.innsendingsId!!

	fun vedleggsliste(): VedleggslisteTestdata {
		val vedleggsListe = soknad.vedleggsListe
		return VedleggslisteTestdata(
			innsendingsId,
			vedleggsListe
				.map { VedleggTestdata(it.id!!, it.vedleggsnr!!) },
			sendinnFilApi,
		)
	}
}

class VedleggslisteTestdata(
	private val innsendingsId: String,
	private val vedleggIdListe: List<VedleggTestdata>,
	private val sendinnFilApi: SendinnFilApi,
) {
	fun verifyHasSize(expectedSize: Int): VedleggslisteTestdata =
		if (expectedSize == vedleggIdListe.size) this
		else throw Exception("Forventer $expectedSize vedlegg, men søknad har ${vedleggIdListe.size}.")

	fun lastOppFil(index: Int, filNavn: String, path: String = "src/test/resources"): VedleggslisteTestdata {
		val file = File("$path/$filNavn")
		sendinnFilApi.lagreFil(innsendingsId, vedleggIdListe[index].id, file)
		return this
	}

	fun lastOppFil(index: Int, file: File): VedleggslisteTestdata {
		sendinnFilApi.lagreFil(innsendingsId, vedleggIdListe[index].id, file)
		return this
	}

}

data class VedleggTestdata(
	val id: Long,
	val vedleggsnr: String,
)
