package com.asistan.cip;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AgentAnalysisClient {
    static final int MAX_AGENTS = 8;
    static final int MAX_SELECTED_AGENTS = 3;
    static final int MAX_AGENT_NAME_CHARACTERS = 120;
    static final int MAX_PROMPT_BYTES = 16 * 1024;
    static final int MAX_TOTAL_PROMPT_BYTES = 48 * 1024;
    static final int MAX_INSTRUCTION_CHARACTERS = 4_000;
    static final int MAX_REQUEST_BYTES = 384 * 1024;
    static final int MAX_ANALYSIS_CHARACTERS = 6_000;
    static final int MAX_SYNTHESIS_CHARACTERS = 10_000;

    static final class Agent {
        final String id;
        final String name;
        final String prompt;

        Agent(String id, String name, String prompt) {
            this.id = id;
            this.name = name;
            this.prompt = prompt;
        }
    }

    static final class Result {
        final List<String> selectedAgents;
        final List<String> analysisAgentIds;
        final List<String> analyses;
        final String synthesis;

        Result(List<String> selectedAgents, List<String> analysisAgentIds,
               List<String> analyses, String synthesis) {
            this.selectedAgents = selectedAgents;
            this.analysisAgentIds = analysisAgentIds;
            this.analyses = analyses;
            this.synthesis = synthesis;
        }
    }

    private AgentAnalysisClient() {
    }

    static JSONObject buildRequest(String instruction, List<Agent> agents, boolean consent)
            throws JSONException {
        if (!consent) {
            throw new IllegalArgumentException("Uzman analizi için açık onay gereklidir.");
        }
        String safeInstruction = instruction == null ? "" : instruction.trim();
        if (safeInstruction.isEmpty() || safeInstruction.length() > MAX_INSTRUCTION_CHARACTERS
                || safeInstruction.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Analiz talimatı boş olamaz veya 4.000 karakteri aşamaz.");
        }
        if (agents == null || agents.isEmpty() || agents.size() > MAX_AGENTS) {
            throw new IllegalArgumentException("1 ile 8 arasında .md ajan dosyası seç.");
        }

        Set<String> ids = new HashSet<>();
        int totalPromptBytes = 0;
        JSONArray submittedAgents = new JSONArray();
        for (Agent agent : agents) {
            if (agent == null || agent.id == null || !agent.id.matches("[A-Za-z0-9._-]{1,64}")
                    || !ids.add(agent.id) || agent.name == null || agent.name.trim().isEmpty()
                    || agent.name.length() > MAX_AGENT_NAME_CHARACTERS
                    || hasControlCharacter(agent.name) || agent.prompt == null
                    || agent.prompt.trim().isEmpty() || agent.prompt.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Ajan kimliği, adı veya tam metni geçersiz.");
            }
            int promptBytes = agent.prompt.getBytes(StandardCharsets.UTF_8).length;
            if (promptBytes > MAX_PROMPT_BYTES) {
                throw new IllegalArgumentException("Her ajan metni en fazla 16 KiB olabilir.");
            }
            totalPromptBytes += promptBytes;
            if (totalPromptBytes > MAX_TOTAL_PROMPT_BYTES) {
                throw new IllegalArgumentException("Ajan metinlerinin toplamı 48 KiB sınırını aşamaz.");
            }
            JSONObject submittedAgent = new JSONObject();
            submittedAgent.put("id", agent.id);
            submittedAgent.put("name", agent.name.trim());
            submittedAgent.put("prompt", agent.prompt);
            submittedAgents.put(submittedAgent);
        }

        JSONObject request = new JSONObject();
        request.put("instruction", safeInstruction);
        request.put("consent", true);
        request.put("agents", submittedAgents);
        if (request.toString().getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Analiz isteği 384 KiB sınırını aşıyor.");
        }
        return request;
    }

    static Result parseResponse(String responseJson, List<Agent> submittedAgents)
            throws JSONException {
        if (responseJson == null
                || responseJson.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("Sunucu yanıtı izin verilen boyutu aşıyor.");
        }
        JSONObject response = new JSONObject(responseJson);
        JSONArray selected = response.getJSONArray("selected_agents");
        JSONArray analyses = response.getJSONArray("analyses");
        String synthesis = response.getString("synthesis");
        if (selected.length() > MAX_SELECTED_AGENTS || analyses.length() != selected.length()
                || !validText(synthesis, MAX_SYNTHESIS_CHARACTERS)) {
            throw new IllegalArgumentException("Sunucudan geçersiz analiz yanıtı alındı.");
        }

        List<String> selectedIds = new ArrayList<>();
        Set<String> submittedIds = new HashSet<>();
        for (Agent agent : submittedAgents) {
            submittedIds.add(agent.id);
        }
        Set<String> uniqueSelected = new HashSet<>();
        for (int i = 0; i < selected.length(); i++) {
            JSONObject selectedAgent = selected.getJSONObject(i);
            String id = selectedAgent.getString("id");
            String name = selectedAgent.getString("name");
            Agent submitted = findAgent(submittedAgents, id);
            if (submitted == null || !submittedIds.contains(id) || !uniqueSelected.add(id)
                    || !submitted.name.trim().equals(name)) {
                throw new IllegalArgumentException("Sunucudan geçersiz analiz yanıtı alındı.");
            }
            selectedIds.add(id);
        }

        List<String> parsedAnalyses = new ArrayList<>();
        List<String> analysisAgentIds = new ArrayList<>();
        Set<String> uniqueAnalyses = new HashSet<>();
        for (int i = 0; i < analyses.length(); i++) {
            JSONObject analysis = analyses.getJSONObject(i);
            String id = analysis.getString("agent_id");
            String text = analysis.getString("analysis");
            if (!uniqueSelected.contains(id) || !uniqueAnalyses.add(id)
                    || !validText(text, MAX_ANALYSIS_CHARACTERS)) {
                throw new IllegalArgumentException("Sunucudan geçersiz analiz yanıtı alındı.");
            }
            analysisAgentIds.add(id);
            parsedAnalyses.add(text);
        }
        if (!uniqueAnalyses.equals(uniqueSelected)) {
            throw new IllegalArgumentException("Sunucudan geçersiz analiz yanıtı alındı.");
        }
        return new Result(selectedIds, analysisAgentIds, parsedAnalyses, synthesis);
    }

    static String configurationFingerprint(String serverUrl, String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((serverUrl + "\n" + apiKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Güvenli özetleme kullanılamıyor.", error);
        }
    }

    private static Agent findAgent(List<Agent> agents, String id) {
        for (Agent agent : agents) {
            if (agent.id.equals(id)) {
                return agent;
            }
        }
        return null;
    }

    private static boolean validText(String text, int maxCharacters) {
        return text != null && !text.trim().isEmpty() && text.length() <= maxCharacters
                && text.indexOf('\0') < 0;
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
