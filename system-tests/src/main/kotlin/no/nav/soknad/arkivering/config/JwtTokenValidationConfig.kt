package no.nav.soknad.arkivering.config

import no.nav.security.token.support.client.spring.oauth2.EnableOAuth2Client
import no.nav.security.token.support.spring.api.EnableJwtTokenValidation
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@EnableOAuth2Client(cacheEnabled = true)
@EnableJwtTokenValidation(ignore = [
	"org.springframework",
	"io.swagger",
	"org.springdoc",
	"org.webjars.swagger-ui"
])
@Profile("dev | prod")
@Configuration
class JwtTokenValidationConfig