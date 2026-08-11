package com.example

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface AuthService {
    suspend fun verify(idToken: String): String?
}

// Relies on FirebaseApp already being initialized (FirestoreService does this).
class FirebaseAuthService : AuthService {
    override suspend fun verify(idToken: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                FirebaseAuth.getInstance().verifyIdToken(idToken).uid
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class FirebaseUserInfo(val email: String?, val displayName: String?)

suspend fun lookupUserInfo(uid: String): FirebaseUserInfo? {
    return withContext(Dispatchers.IO) {
        try {
            val user = FirebaseAuth.getInstance().getUser(uid)
            FirebaseUserInfo(email = user.email, displayName = user.displayName)
        } catch (_: Exception) {
            null
        }
    }
}
