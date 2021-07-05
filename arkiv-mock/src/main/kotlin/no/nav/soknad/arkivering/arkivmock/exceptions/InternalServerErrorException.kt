package no.nav.soknad.arkivering.arkivmock.exceptions

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(code = HttpStatus.INTERNAL_SERVER_ERROR, reason = "Mocked exception")
class InternalServerErrorException : Exception()
