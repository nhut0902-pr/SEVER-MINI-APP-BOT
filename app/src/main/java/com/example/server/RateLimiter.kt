package com.example.server

class RateLimiter {
    // Stores timestamps of requests for each IP address
    private val clientRequests = mutableMapOf<String, MutableList<Long>>()

    @Synchronized
    fun allowRequest(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = clientRequests.getOrPut(ip) { mutableListOf() }
        
        // Retain only requests made in the last 60 seconds
        timestamps.removeAll { now - it > 60000 }
        
        // Limit to 10 requests per minute
        if (timestamps.size >= 10) {
            return false
        }
        
        timestamps.add(now)
        return true
    }
}
