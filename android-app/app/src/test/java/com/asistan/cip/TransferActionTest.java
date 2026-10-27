package com.asistan.cip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class TransferActionTest {
    private static final String HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void verifiesExactServerChallengeAndAllowsApprovalOnlyForAwaitingAction() throws Exception {
        String challenge = TransferAction.challenge("action-1", "pair-1", "nonce-1",
                "pc_to_phone", "dosya.txt", 12L, HASH, "2026-10-28T00:00:00Z");
        TransferAction action = TransferAction.fromJson(actionJson(
                "awaiting_approval", challenge), "pair-1");

        assertTrue(action.awaitsApproval());
        assertEquals(challenge, action.challenge);
        assertEquals(12L, action.size);
    }

    @Test
    public void acceptsNullChallengeForNonApprovalStatesWithoutEnablingBiometricApproval() throws Exception {
        TransferAction action = TransferAction.fromJson(actionJson("ready", null), "pair-1");
        assertFalse(action.awaitsApproval());
        assertNull(action.challenge);
    }

    @Test
    public void rejectsMismatchedChallengeAndWrongPairing() throws Exception {
        try {
            TransferAction.fromJson(actionJson("awaiting_approval", "untrusted challenge"), "pair-1");
            fail("Expected challenge mismatch to be rejected.");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("challenge"));
        }

        String challenge = TransferAction.challenge("action-1", "pair-1", "nonce-1",
                "pc_to_phone", "dosya.txt", 12L, HASH, "2026-10-28T00:00:00Z");
        try {
            TransferAction.fromJson(actionJson("awaiting_approval", challenge), "other-pair");
            fail("Expected pairing mismatch to be rejected.");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("eşleşme"));
        }
    }

    @Test
    public void enforcesFileSizeLimitAndStrictActionEnvelope() throws Exception {
        JSONObject tooLarge = actionJson("ready", null);
        tooLarge.put("size", TransferAction.MAX_FILE_SIZE + 1);
        try {
            TransferAction.fromJson(tooLarge, "pair-1");
            fail("Expected oversize action to be rejected.");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("20 MiB"));
        }

        try {
            TransferApi.parseActions("{}", "pair-1");
            fail("Expected a strict object envelope.");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("actions"));
        }
    }

    private JSONObject actionJson(String state, String challenge) throws Exception {
        JSONObject json = new JSONObject();
        json.put("action_id", "action-1");
        json.put("pairing_id", "pair-1");
        json.put("nonce", "nonce-1");
        json.put("direction", "pc_to_phone");
        json.put("filename", "dosya.txt");
        json.put("size", 12L);
        json.put("sha256", HASH);
        json.put("created_at", "2026-10-27T23:00:00Z");
        json.put("expires_at", "2026-10-28T00:00:00Z");
        json.put("state", state);
        json.put("challenge", challenge == null ? JSONObject.NULL : challenge);
        return json;
    }
}
