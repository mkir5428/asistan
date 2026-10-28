package com.asistan.cip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AgentAnalysisClientTest {
    @Test
    public void buildsRequestWithExactSelectedPromptsOnlyAfterConsent() throws Exception {
        AgentAnalysisClient.Agent agent =
                new AgentAnalysisClient.Agent("agent-1", "reviewer", "# Full prompt\nNo tools.");

        JSONObject request = AgentAnalysisClient.buildRequest(
                "Review this.", Collections.singletonList(agent), true);

        assertEquals("Review this.", request.getString("instruction"));
        assertTrue(request.getBoolean("consent"));
        JSONArray agents = request.getJSONArray("agents");
        assertEquals(1, agents.length());
        assertEquals("# Full prompt\nNo tools.", agents.getJSONObject(0).getString("prompt"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentAnalysisClient.buildRequest("Review this.",
                        Collections.singletonList(agent), false));
    }

    @Test
    public void enforcesAgentCountPromptLimitsAndValidatesResponseAgainstSubmittedPrompts()
            throws Exception {
        AgentAnalysisClient.Agent agent =
                new AgentAnalysisClient.Agent("agent-1", "reviewer", "Prompt");
        assertThrows(IllegalArgumentException.class,
                () -> AgentAnalysisClient.buildRequest("Review this.",
                        Collections.emptyList(), true));
        assertThrows(IllegalArgumentException.class,
                () -> AgentAnalysisClient.buildRequest("Review this.",
                        Collections.singletonList(new AgentAnalysisClient.Agent(
                                "agent-1", "reviewer", "x".repeat(
                                        AgentAnalysisClient.MAX_PROMPT_BYTES + 1))), true));

        String response = "{\"selected_agents\":[{\"id\":\"agent-1\",\"name\":\"reviewer\"}],"
                + "\"analyses\":[{\"agent_id\":\"agent-1\",\"analysis\":\"Looks good.\"}],"
                + "\"synthesis\":\"Keep the prompt.\"}";
        AgentAnalysisClient.Result parsed = AgentAnalysisClient.parseResponse(
                response, Collections.singletonList(agent));
        assertEquals("agent-1", parsed.selectedAgents.get(0));
        assertEquals("agent-1", parsed.analysisAgentIds.get(0));
        assertEquals("Looks good.", parsed.analyses.get(0));
        assertEquals("Keep the prompt.", parsed.synthesis);

        String unknownAgentResponse = response.replace("agent-1", "other-agent");
        assertThrows(IllegalArgumentException.class,
                () -> AgentAnalysisClient.parseResponse(
                        unknownAgentResponse, Collections.singletonList(agent)));
        assertFalse(AgentAnalysisClient.configurationFingerprint("https://a.test", "key")
                .equals(AgentAnalysisClient.configurationFingerprint("https://a.test", "other")));
        assertTrue(AgentAnalysisClient.configurationFingerprint("https://a.test", "key")
                .matches("[a-f0-9]{64}"));
    }
}
