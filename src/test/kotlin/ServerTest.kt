package com.example

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `health check reports ok without auth`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `collections list rejects missing token`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/collections").status)
    }

    @Test
    fun `collections list rejects invalid token`() = testApplication {
        configure()
        val response = client.get("/collections") {
            header("Authorization", "Bearer not-a-real-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `create collection rejects missing token`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/collections").status)
    }

    @Test
    fun `delete collection rejects missing token`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/collections/some-id").status)
    }

    @Test
    fun `index faces rejects missing token`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/indexFaces").status)
    }

    @Test
    fun `categorize rejects missing token`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.Unauthorized, client.post("/categorize").status)
    }

    @Test
    fun `categorization results rejects missing token`() = testApplication {
        configure()
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/categorization-results?collectionId=some-id").status
        )
    }
}
