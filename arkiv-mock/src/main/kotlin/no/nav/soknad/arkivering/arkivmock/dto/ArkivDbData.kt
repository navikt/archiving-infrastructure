package no.nav.soknad.arkivering.arkivmock.dto

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import javax.persistence.Entity
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table(name = "arkiv")
class ArkivDbData(
	@Id
	val id: String,
	val title: String,
	val tema: String,
	val timesaved: Long
) {
	override fun toString(): String {
		val mapper = ObjectMapper()
		mapper.enable(SerializationFeature.INDENT_OUTPUT)
		mapper.findAndRegisterModules()
		return mapper.writeValueAsString(this)
	}
}
