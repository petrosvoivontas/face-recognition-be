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
