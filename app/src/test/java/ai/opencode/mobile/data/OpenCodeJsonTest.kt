package ai.opencode.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeJsonTest {
    @Test
    fun parsesCurrentSessionAndStatusShapes() {
        val sessions = OpenCodeJson.parseSessions(
            """
            [
              {
                "id":"ses_1",
                "title":"Mobile sync",
                "directory":"C:/work/demo",
                "projectID":"project-1",
                "time":{"created":100,"updated":200},
                "summary":{"additions":12,"deletions":3,"files":2},
                "model":{"providerID":"anthropic","id":"claude"}
              }
            ]
            """.trimIndent(),
        )
        val statuses = OpenCodeJson.parseStatuses(
            """{"ses_1":{"type":"retry","attempt":2,"message":"rate limited","next":500}}""",
        )

        assertEquals("Mobile sync", sessions.single().title)
        assertEquals(2, sessions.single().summary?.files)
        assertEquals("claude", sessions.single().model?.modelId)
        assertEquals(SessionStatus.Type.RETRY, statuses.getValue("ses_1").type)
        assertEquals(2, statuses.getValue("ses_1").attempt)
    }

    @Test
    fun parsesStreamingMessagePartsAndBlockingRequests() {
        val messages = OpenCodeJson.parseMessages(
            """
            [
              {
                "info":{"id":"msg_1","sessionID":"ses_1","role":"assistant","time":{"created":10},"parentID":"msg_0","modelID":"gpt","providerID":"openai","mode":"build","agent":"build","path":{"cwd":".","root":"."},"cost":0,"tokens":{"input":1,"output":1,"reasoning":0,"cache":{"read":0,"write":0}}},
                "parts":[
                  {"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"text","text":"hello"},
                  {"id":"prt_2","sessionID":"ses_1","messageID":"msg_1","type":"tool","callID":"call_1","tool":"bash","state":{"status":"completed","input":{"command":"pwd"},"output":"C:/work","title":"Run pwd","metadata":{},"time":{"start":10,"end":11}}}
                ]
              }
            ]
            """.trimIndent(),
        )
        val permission = OpenCodeJson.parsePermission(
            OpenCodeJson.objectValue(
                """{"id":"per_1","sessionID":"ses_1","permission":"bash","patterns":["git status"],"always":["git *"],"metadata":{}}""",
            ),
        )
        val question = OpenCodeJson.parseQuestionRequest(
            OpenCodeJson.objectValue(
                """{"id":"que_1","sessionID":"ses_1","questions":[{"question":"Ship?","header":"Release","options":[{"label":"Yes","description":"Deploy now"}],"multiple":false,"custom":true}]}""",
            ),
        )

        assertEquals("hello", messages.single().parts.first().text)
        assertEquals("completed", messages.single().parts[1].toolStatus)
        assertTrue(messages.single().parts[1].toolInput.orEmpty().contains("pwd"))
        assertEquals("git status", permission?.patterns?.single())
        assertEquals("Ship?", question?.questions?.single()?.question)
    }

    @Test
    fun acceptsVersionedPermissionEventAndUnknownPartFields() {
        val event = OpenCodeJson.parseEvent(
            """{"id":"evt_1","type":"permission.v2.asked","properties":{"id":"per_2","sessionID":"ses_1","action":"edit","resources":["src/**"],"save":["src/**"],"metadata":{"source":"tool"}}}""",
        )
        val request = OpenCodeJson.parsePermission(event.properties)
        val part = OpenCodeJson.parsePart(
            OpenCodeJson.objectValue(
                """{"id":"prt_x","sessionID":"ses_1","messageID":"msg_1","type":"future-part","future":{"value":1}}""",
            ),
        )

        assertEquals("permission.v2.asked", event.type)
        assertEquals("edit", request?.permission)
        assertEquals("src/**", request?.patterns?.single())
        assertEquals("future-part", part?.type)
    }

    @Test
    fun unwrapsGlobalEventAndKeepsDirectoryRouting() {
        val event = OpenCodeJson.parseEvent(
            """{"directory":"C:/work/project","payload":{"id":"evt_2","type":"todo.updated","properties":{"sessionID":"ses_1","todos":[]}}}""",
        )

        assertEquals("evt_2", event.id)
        assertEquals("todo.updated", event.type)
        assertEquals("ses_1", event.properties.string("sessionID"))
        assertEquals("C:/work/project", event.directory)
    }

    @Test
    fun parsesCurrentProtocolDataEnvelope() {
        val event = OpenCodeJson.parseEvent(
            """{"id":"evt_3","type":"session.next.text.delta","location":{"directory":"/srv/app"},"data":{"sessionID":"ses_1","assistantMessageID":"msg_1","textID":"text_1","delta":"hello"}}""",
        )

        assertEquals("session.next.text.delta", event.type)
        assertEquals("hello", event.properties.string("delta"))
        assertEquals("/srv/app", event.directory)
    }

    @Test
    fun parsesConnectedModelsAndDesktopBridge() {
        val catalog = OpenCodeJson.parseModels(
            """
            {
              "connected":["connected"],
              "all":[
                {"id":"connected","name":"Connected","models":{"m1":{"name":"Model One (latest)","family":"one","release_date":"2026-08-01","status":"active"}}},
                {"id":"offline","name":"Offline","models":{"m2":{"name":"Model Two","status":"active"}}}
              ]
            }
            """.trimIndent(),
            "connected/m1",
        )
        val bridge = OpenCodeJson.parseDesktopBridge(
            """
            {
              "desktop":{"desktopUsesSharedServer":false,"privateOpenSessionIds":["ses_1"],"sharedOpenSessionIds":["ses_2"],"privateRecentSessionId":"ses_1","sharedRecentSessionId":"ses_2"},
              "network":{"serverUrls":["http://192.168.1.2:4096"]},
              "models":{"user":[{"providerID":"connected","modelID":"m1","visibility":"show"}],"recent":[{"providerID":"connected","modelID":"m1"}]}
            }
            """.trimIndent(),
        )

        assertEquals(listOf("connected/m1"), catalog.models.map { it.key })
        assertEquals("connected/m1", catalog.defaultModelKey)
        assertTrue(catalog.models.single().latest)
        assertEquals(setOf("ses_1"), bridge.privateOpenSessionIds)
        assertEquals(setOf("ses_2"), bridge.sharedOpenSessionIds)
        assertEquals("http://192.168.1.2:4096", bridge.serverUrls.single())
        assertEquals("show", bridge.modelPreferences.single().visibility)
    }
}
