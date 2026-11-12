package com.pitgpt.galaxy.core.repository

import com.pitgpt.galaxy.core.model.LilaIdentity

/** Provides the canonical Lila identity independently of any model or execution runtime. */
interface LilaIdentityRepository {
    suspend fun getPrimaryIdentity(): LilaIdentity
}
