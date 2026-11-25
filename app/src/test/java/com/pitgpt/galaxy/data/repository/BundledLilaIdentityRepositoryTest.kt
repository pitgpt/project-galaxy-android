package com.pitgpt.galaxy.data.repository

import com.pitgpt.galaxy.core.model.LilaIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledLilaIdentityRepositoryTest {
    @Test
    fun providesStablePrimaryIdentityAndCurrentSchema() = runBlocking {
        val identity = BundledLilaIdentityRepository().getPrimaryIdentity()

        assertEquals(LilaIdentity.PRIMARY_ID, identity.id)
        assertEquals(LilaIdentity.CURRENT_SCHEMA_VERSION, identity.schemaVersion)
        assertEquals("Лила", identity.displayName)
        assertTrue(identity.persona.isNotBlank())
    }

    @Test
    fun identityContainsCuratedBehaviorAndGoals() = runBlocking {
        val identity = BundledLilaIdentityRepository().getPrimaryIdentity()

        assertTrue(identity.communicationPrinciples.isNotEmpty())
        assertTrue(identity.longTermGoals.isNotEmpty())
        assertTrue(identity.decisionPreferences.isNotEmpty())
        assertFalse(identity.communicationPrinciples.any(String::isBlank))
    }

    @Test
    fun identityDoesNotDependOnCharacterOrModelProvider() = runBlocking {
        val identity = BundledLilaIdentityRepository().getPrimaryIdentity()

        assertEquals(LilaIdentity.PRIMARY_ID, identity.id)
        assertEquals(null, identity.visualReferenceId)
        assertEquals(null, identity.voiceReferenceId)
    }
}
