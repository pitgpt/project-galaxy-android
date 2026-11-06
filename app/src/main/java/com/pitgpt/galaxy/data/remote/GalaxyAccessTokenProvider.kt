package com.pitgpt.galaxy.data.remote

fun interface GalaxyAccessTokenProvider {
    suspend fun accessToken(): String?
}
