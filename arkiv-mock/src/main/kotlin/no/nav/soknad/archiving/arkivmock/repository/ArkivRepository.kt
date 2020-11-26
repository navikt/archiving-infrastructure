package no.nav.soknad.archiving.arkivmock.repository

import no.nav.soknad.archiving.arkivmock.dto.ArkivDbData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ArkivRepository : JpaRepository<ArkivDbData, String>
