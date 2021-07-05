package no.nav.soknad.arkivering.arkivmock.repository

import no.nav.soknad.arkivering.arkivmock.dto.ArkivDbData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ArkivRepository : JpaRepository<ArkivDbData, String>
