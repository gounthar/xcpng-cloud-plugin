package io.jenkins.plugins.xcpng.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link XoRestClient} against a scripted transport, no appliance. The transport dispatches on
 * method plus path and records every request, so a test can assert both what came back and <em>what was
 * sent</em> -- which is where this backend's two most expensive findings live: the create body must not
 * carry {@code vifs}, and the sizing PATCH must not carry {@code cpusStaticMax}.
 */
class XoRestClientTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String POOL = "355ee47d-ff4c-4924-3db2-fd86ae629677";
    private static final String TEMPLATE_UUID = "5436f445-a341-cc10-7312-e1077b0c4e69";
    private static final String CLONE = "f07ab729-c0e8-721c-45ec-f11276377030";

    // -- resolveTemplate --------------------------------------------------

    @Test
    void resolveTemplateReturnsThePoolAndTheBareUuid() {
        ScriptedRest t = new ScriptedRest();
        VmRef ref = new XoRestClient(t).resolveTemplate("jenkins-agent-debian13-v7");
        // Both halves, in the one order the create route accepts. The pool-prefixed id XO also carries
        // for this object 404s on that route, which is why it must not be what comes back here.
        assertEquals(POOL + "/" + TEMPLATE_UUID, ref.value());
    }

    @Test
    void resolveTemplateFailsWhenNoTemplateCarriesTheName() {
        ScriptedRest t = new ScriptedRest();
        HypervisorException e =
                assertThrows(HypervisorException.class, () -> new XoRestClient(t).resolveTemplate("does-not-exist"));
        assertTrue(e.getMessage().contains("no template named"), e.getMessage());
    }

    @Test
    void resolveTemplateFailsWhenTheNameIsAmbiguous() {
        ScriptedRest t = new ScriptedRest();
        t.templates.add(template("dup-2", "jenkins-agent-debian13-v7", POOL));
        t.templates.add(template("dup-3", "jenkins-agent-debian13-v7", POOL));
        HypervisorException e = assertThrows(
                HypervisorException.class, () -> new XoRestClient(t).resolveTemplate("jenkins-agent-debian13-v7"));
        assertTrue(e.getMessage().contains("3 templates"), e.getMessage());
    }

    @Test
    void resolveTemplateRefusesATemplateThatNamesNoUuidOrNoPool() {
        // The failure this refuses to paper over: falling back to the object's `id` here would produce a
        // handle that clones fine in a test and 404s against a real appliance.
        ScriptedRest t = new ScriptedRest();
        t.templates.clear();
        t.templates.add(Map.of("id", POOL + "-" + TEMPLATE_UUID, "name_label", "half-a-template"));
        HypervisorException e =
                assertThrows(HypervisorException.class, () -> new XoRestClient(t).resolveTemplate("half-a-template"));
        assertTrue(e.getMessage().contains("no uuid or no pool"), e.getMessage());
    }

    @Test
    void resolveTemplateRefusesAnAnswerThatIsNotAList() {
        ScriptedRest t = new ScriptedRest();
        t.templatesBody = "{\"error\":\"not a list\"}";
        assertThrows(HypervisorException.class, () -> new XoRestClient(t).resolveTemplate("anything"));
    }

    // -- cloneFromTemplate ------------------------------------------------

    @Test
    void cloneCreatesThroughThePoolRouteWithTheBareUuidAndNoVifs() {
        ScriptedRest t = new ScriptedRest();
        XoRestClient c = new XoRestClient(t);
        VmRef vm = c.cloneFromTemplate(c.resolveTemplate("jenkins-agent-debian13-v7"), spec());

        assertEquals(CLONE, vm.value());
        Call create = t.only("POST", "/rest/v0/pools/" + POOL + "/actions/create_vm?sync=true");
        assertEquals(TEMPLATE_UUID, create.json().path("template").asText());
        assertTrue(create.json().path("clone").asBoolean(), "must be a copy-on-write clone, not a full copy");
        assertFalse(create.json().path("boot").asBoolean(), "the caller starts it, after the seed is written");
        // The finding this backend exists around: this route inherits the template's VIFs, and passing
        // them is additive, so a clone that was handed its own network comes up with two NICs.
        assertFalse(create.json().has("vifs"), "create_vm must not be passed vifs: " + create.body);
        assertFalse(create.json().has("VIFs"), "nor under the JSON-RPC spelling: " + create.body);
    }

    @Test
    void cloneSizesThroughCpusAloneAndNeverCpusStaticMax() {
        ScriptedRest t = new ScriptedRest();
        XoRestClient c = new XoRestClient(t);
        c.cloneFromTemplate(c.resolveTemplate("jenkins-agent-debian13-v7"), spec());

        Call patch = t.only("PATCH", "/rest/v0/vms/" + CLONE);
        assertEquals(2, patch.json().path("cpus").asInt());
        assertEquals(2048L, patch.json().path("memory").asLong());
        // XO fires the setters for the values it is given concurrently, and its own constraint machinery
        // already raises the max when cpus needs more room. Sending both would race a VCPUs_max write
        // against a still-higher VCPUs_at_startup on the shrinking case, which XAPI rejects.
        assertFalse(patch.json().has("cpusStaticMax"), "sizing must leave the max to XO: " + patch.body);
    }

    @Test
    void cloneSeedsGuestDataUnderTheVmDataPrefixInOneCallBeforeTheVmStarts() {
        ScriptedRest t = new ScriptedRest();
        XoRestClient c = new XoRestClient(t);
        VmRef vm = c.cloneFromTemplate(
                c.resolveTemplate("jenkins-agent-debian13-v7"),
                spec(Map.of("url", "http://controller/", "name", "agent-1", "secret", "s3cr3t")));
        c.start(vm);

        Call patch = t.only("PATCH", "/rest/v0/vms/" + CLONE);
        JsonNode seed = patch.json().path("xenStoreData");
        assertEquals("s3cr3t", seed.path("vm-data/jenkins/secret").asText());
        assertEquals("agent-1", seed.path("vm-data/jenkins/name").asText());
        // Only vm-data/* reaches the guest, and the write has to land before the VM starts: setting
        // xenstore-data on a running VM does not propagate.
        assertTrue(t.indexOf("PATCH", "/rest/v0/vms/" + CLONE)
                < t.indexOf("POST", "/rest/v0/vms/" + CLONE + "/actions/start?sync=true"));
    }

    @Test
    void cloneStampsTheOwnerTagAndPercentEncodesTheCloudName() {
        ScriptedRest t = new ScriptedRest();
        XoRestClient c = new XoRestClient(t);
        c.cloneFromTemplate(
                c.resolveTemplate("jenkins-agent-debian13-v7"),
                new ProvisionSpec("agent-1", 2, 2048L, null, null, null, Map.of(), "lab/build agents"));

        // A slash in an operator's cloud name would otherwise address a different route entirely, which
        // answers something rather than erroring, leaving a VM that no sweep can find.
        assertTrue(
                t.paths().contains("PUT /rest/v0/vms/" + CLONE + "/tags/xcpng-cloud%3Alab%2Fbuild%20agents"),
                t.paths().toString());
    }

    @Test
    void cloneWithNoOwnerStampsNoTag() {
        ScriptedRest t = new ScriptedRest();
        XoRestClient c = new XoRestClient(t);
        c.cloneFromTemplate(c.resolveTemplate("jenkins-agent-debian13-v7"), spec());
        assertTrue(
                t.paths().stream().noneMatch(p -> p.contains("/tags/")),
                t.paths().toString());
    }

    @Test
    void cloneRejectsPlacementHintAndDiskBytesBeforeCreatingAnything() {
        ScriptedRest t = new ScriptedRest();
        XoRestClient c = new XoRestClient(t);
        VmRef template = c.resolveTemplate("jenkins-agent-debian13-v7");
        int reads = t.calls.size();

        assertThrows(
                HypervisorException.class,
                () -> c.cloneFromTemplate(template, new ProvisionSpec("a", 2, 2048L, null, "host-3", null)));
        assertThrows(
                HypervisorException.class,
                () -> c.cloneFromTemplate(template, new ProvisionSpec("a", 2, 2048L, 8_000_000_000L, null, null)));
        assertEquals(reads, t.calls.size(), "a rejected spec must not reach the appliance at all");
    }

    @Test
    void aHandleFromTheOtherBackendIsRefusedRatherThanUsed() {
        ScriptedRest t = new ScriptedRest();
        HypervisorException e = assertThrows(
                HypervisorException.class,
                () -> new XoRestClient(t).cloneFromTemplate(new VmRef("OpaqueRef:1234"), spec()));
        assertTrue(e.getMessage().contains("not a template handle"), e.getMessage());
        assertTrue(t.calls.isEmpty(), "must be refused locally, before any request");
    }

    @Test
    void cloneDestroysThePartialCloneWhenConfiguringFails() {
        ScriptedRest t = new ScriptedRest();
        t.fail("PATCH", "/rest/v0/vms/" + CLONE, 422, "{\"error\":\"invalid parameters\"}");
        XoRestClient c = new XoRestClient(t);
        HypervisorException e = assertThrows(
                HypervisorException.class,
                () -> c.cloneFromTemplate(c.resolveTemplate("jenkins-agent-debian13-v7"), spec()));

        assertTrue(e.getMessage().contains("invalid parameters"), e.getMessage());
        // The clone already existed when the sizing was rejected. Leaving it is the leak this catch exists
        // to prevent, and an XO-made clone carries no other_config marker for tools/reaper.py to find.
        assertEquals(List.of(CLONE), t.destroyed, "the partly configured clone must be reclaimed");
    }

    @Test
    void cloneInterruptedWhileConfiguringStillDestroysItAndKeepsTheInterrupt() {
        ScriptedRest t = new ScriptedRest();
        // The clone exists, then the thread is interrupted, so every later call fails on the interrupt
        // exactly as it would against an appliance. The cleanup must still reclaim the clone: this is the
        // case that leaks a VM and its copy-on-write disks.
        t.interruptOn("PATCH", "/rest/v0/vms/" + CLONE);
        XoRestClient c = new XoRestClient(t);
        try {
            assertThrows(
                    HypervisorException.class,
                    () -> c.cloneFromTemplate(c.resolveTemplate("jenkins-agent-debian13-v7"), spec()));
            // Not "a DELETE was issued": on a still-interrupted thread the request never leaves, and
            // asserting on the recorded call would certify this exact leak as cleaned up.
            assertEquals(List.of(CLONE), t.destroyed, "the clone and its copy-on-write disks must be reclaimed");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt must be handed back to the caller");
        } finally {
            Thread.interrupted(); // clear it, or the next test in this thread starts interrupted
        }
    }

    // -- the rest of the verbs --------------------------------------------

    @Test
    void clearGuestSecretRemovesThatOneKeyAndNothingElse() {
        ScriptedRest t = new ScriptedRest();
        new XoRestClient(t).clearGuestSecret(new VmRef(CLONE));

        JsonNode seed = t.only("PATCH", "/rest/v0/vms/" + CLONE).json().path("xenStoreData");
        assertEquals(1, seed.size(), "url and name must survive: " + seed);
        assertTrue(seed.path("vm-data/jenkins/secret").isNull(), "a null value is the per-key delete: " + seed);
    }

    @Test
    void primaryIpAddressReportsARoutableAddress() {
        ScriptedRest t = new ScriptedRest();
        t.powerState = "Running";
        t.mainIpAddress = "192.168.1.42";
        assertEquals(Optional.of("192.168.1.42"), new XoRestClient(t).primaryIpAddress(new VmRef(CLONE)));
    }

    @Test
    void primaryIpAddressIgnoresLinkLocalAcrossTheWholeTenBitPrefix() {
        // fe80::/10 runs to febf:ffff:..., so fe90:: and febf:: are link-local too. The prefix test this
        // pins against said /10 and matched "fe80:", which is /16, and read the rest as routable.
        for (String linkLocal : new String[] {
            "fe80::cd1c:1",
            "fe90::1",
            "feaa::1",
            "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
            "169.254.3.4",
            "fe80::1%eth0"
        }) {
            ScriptedRest t = new ScriptedRest();
            t.powerState = "Running";
            t.mainIpAddress = linkLocal;
            assertEquals(
                    Optional.empty(),
                    new XoRestClient(t).primaryIpAddress(new VmRef(CLONE)),
                    linkLocal + " is link-local");
        }
        // The control: an address one bit outside the prefix must still come back, or the filter above
        // proves nothing except that this method can return empty.
        ScriptedRest routable = new ScriptedRest();
        routable.powerState = "Running";
        routable.mainIpAddress = "fec0::1";
        assertEquals(Optional.of("fec0::1"), new XoRestClient(routable).primaryIpAddress(new VmRef(CLONE)));
    }

    @Test
    void primaryIpAddressIgnoresAnythingThatIsNotAnAddressLiteral() {
        // Not merely "returns empty": the value must never reach a resolver. A hostname here would be a
        // DNS lookup on a provisioning thread, on a string the appliance supplied.
        ScriptedRest t = new ScriptedRest();
        t.powerState = "Running";
        t.mainIpAddress = "localhost";
        assertEquals(Optional.empty(), new XoRestClient(t).primaryIpAddress(new VmRef(CLONE)));
    }

    @Test
    void primaryIpAddressIsEmptyUnlessTheVmIsRunning() {
        ScriptedRest t = new ScriptedRest();
        t.powerState = "Halted";
        t.mainIpAddress = "192.168.1.42";
        assertEquals(Optional.empty(), new XoRestClient(t).primaryIpAddress(new VmRef(CLONE)));
    }

    @Test
    void stateMapsEveryPowerStateAndFallsBackToUnknown() {
        assertEquals(VmState.HALTED, stateOf("Halted"));
        assertEquals(VmState.RUNNING, stateOf("Running"));
        assertEquals(VmState.PAUSED, stateOf("Paused"));
        assertEquals(VmState.SUSPENDED, stateOf("Suspended"));
        assertEquals(VmState.UNKNOWN, stateOf("Migrating"));
    }

    @Test
    void startAndStopUseTheSynchronousFormSoTheyReturnOnlyWhenDone() {
        ScriptedRest t = new ScriptedRest();
        XoRestClient c = new XoRestClient(t);
        c.start(new VmRef(CLONE));
        c.stop(new VmRef(CLONE));
        // Without sync=true these routes answer 202 and a task id, and the caller would carry on against a
        // VM that has not started -- which the interface forbids: the verbs are synchronous from the
        // caller's view however much waiting that took underneath.
        assertTrue(
                t.paths().contains("POST /rest/v0/vms/" + CLONE + "/actions/start?sync=true"),
                t.paths().toString());
        assertTrue(
                t.paths().contains("POST /rest/v0/vms/" + CLONE + "/actions/clean_shutdown?sync=true"),
                t.paths().toString());
    }

    @Test
    void destroyTreatsAnAlreadyGoneVmAsDone() {
        ScriptedRest t = new ScriptedRest();
        t.fail("DELETE", "/rest/v0/vms/" + CLONE, 404, "{\"error\":\"no such object\"}");
        new XoRestClient(t).destroyWithDisks(new VmRef(CLONE)); // must not throw
    }

    @Test
    void destroyPropagatesAnythingThatIsNotAMissingObject() {
        ScriptedRest t = new ScriptedRest();
        t.fail("DELETE", "/rest/v0/vms/" + CLONE, 409, "{\"error\":\"incorrect state\"}");
        HypervisorException e =
                assertThrows(HypervisorException.class, () -> new XoRestClient(t).destroyWithDisks(new VmRef(CLONE)));
        assertEquals("incorrect state", e.getErrorCode());
    }

    @Test
    void aXapiErrorReachesTheCallerWithItsCodeAndParameters() {
        // XO passes a XAPI failure through as a 500 whose `error` is the XAPI code itself, which is what
        // lets a caller branch on a code on this backend too.
        ScriptedRest t = new ScriptedRest();
        t.fail(
                "POST",
                "/rest/v0/vms/" + CLONE + "/actions/start?sync=true",
                500,
                "{\"error\":\"VM_BAD_POWER_STATE\",\"data\":[\"VM\",\"Halted\",\"Running\"],"
                        + "\"info\":\"This is a XenServer/XCP-ng error, not an XO error\"}");
        HypervisorException e =
                assertThrows(HypervisorException.class, () -> new XoRestClient(t).start(new VmRef(CLONE)));
        assertEquals("VM_BAD_POWER_STATE", e.getErrorCode());
        assertEquals(List.of("VM", "Halted", "Running"), e.getErrorParams());
    }

    @Test
    void pingProvesAuthenticationRatherThanReachability() {
        ScriptedRest t = new ScriptedRest();
        new XoRestClient(t).ping();
        // /rest/v0/ping is declared Security('none'), so it would answer pong to a wrong or expired token
        // and report a working connection that cannot provision anything.
        assertFalse(
                t.paths().stream().anyMatch(p -> p.endsWith("/rest/v0/ping")),
                t.paths().toString());
        assertTrue(t.paths().contains("GET /rest/v0/pools?fields=id"), t.paths().toString());

        ScriptedRest denied = new ScriptedRest();
        denied.fail("GET", "/rest/v0/pools?fields=id", 401, "{\"error\":\"invalid credentials\"}");
        assertThrows(HypervisorException.class, () -> new XoRestClient(denied).ping());
    }

    @Test
    void anEmptyBodyIsNotMalformed() {
        // 204 is the documented answer to both the sizing PATCH and the tag PUT, and it carries no body.
        ScriptedRest t = new ScriptedRest();
        t.noContent = true;
        XoRestClient c = new XoRestClient(t);
        c.cloneFromTemplate(c.resolveTemplate("jenkins-agent-debian13-v7"), spec());
    }

    @Test
    void aNonJsonAnswerFailsWithWhatAnsweredRatherThanABareParseError() {
        ScriptedRest t = new ScriptedRest();
        t.fail("GET", "/rest/v0/pools?fields=id", 200, "<html><body>Sign in</body></html>");
        HypervisorException e = assertThrows(HypervisorException.class, () -> new XoRestClient(t).ping());
        assertTrue(e.getMessage().contains("Sign in"), e.getMessage());
    }

    @Test
    void closeReleasesNothingBecauseTheTokenIsNotOurs() {
        ScriptedRest t = new ScriptedRest();
        new XoRestClient(t).close();
        assertTrue(t.calls.isEmpty(), "closing must not revoke the operator's token");
    }

    @Test
    void segmentEncodingLeavesUnreservedCharactersAloneAndEscapesTheRest() {
        assertEquals("agent-1.0_x~y", XoRestClient.encodeSegment("agent-1.0_x~y"));
        assertEquals("a%2Fb", XoRestClient.encodeSegment("a/b"));
        assertEquals("a%20b", XoRestClient.encodeSegment("a b"));
        assertEquals("%C3%A9", XoRestClient.encodeSegment("é"));
    }

    // -- helpers ----------------------------------------------------------

    private static VmState stateOf(String powerState) {
        ScriptedRest t = new ScriptedRest();
        t.powerState = powerState;
        return new XoRestClient(t).state(new VmRef(CLONE));
    }

    private static ProvisionSpec spec() {
        return new ProvisionSpec("agent-1", 2, 2048L, null, null, null);
    }

    private static ProvisionSpec spec(Map<String, String> guestData) {
        return new ProvisionSpec("agent-1", 2, 2048L, null, null, null, guestData);
    }

    private static Map<String, Object> template(String id, String nameLabel, String pool) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("uuid", id.equals("t-1") ? TEMPLATE_UUID : id);
        t.put("name_label", nameLabel);
        t.put("$pool", pool);
        return t;
    }

    private record Call(String method, String path, String body) {

        JsonNode json() {
            try {
                return M.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("request body is not JSON: " + body, e);
            }
        }
    }

    /**
     * Answers the shapes a real appliance answers, and records what it was asked. Every response body here
     * is the documented shape of that route, so a test that passes because the fixture was agreeable is
     * visible as a wrong body rather than as a green run.
     */
    private static final class ScriptedRest implements RestTransport {

        final List<Call> calls = new ArrayList<>();
        /**
         * VMs this transport actually destroyed, as opposed to was asked to destroy. The two are not the
         * same and the difference is the whole point: a DELETE issued on an interrupted thread never
         * reaches the appliance, and a fixture that records the attempt reports that leaked clone as
         * reclaimed. Assert on this, never on the recorded call.
         */
        final List<String> destroyed = new ArrayList<>();

        final List<Map<String, Object>> templates = new ArrayList<>();

        String templatesBody;
        String powerState = "Halted";
        String mainIpAddress;
        boolean noContent;

        private final Map<String, RestResponse> failures = new LinkedHashMap<>();
        private final List<String> interrupts = new ArrayList<>();

        ScriptedRest() {
            templates.add(template("t-1", "jenkins-agent-debian13-v7", POOL));
            templates.add(template("t-2", "Debian Trixie 13", POOL));
        }

        void fail(String method, String path, int status, String body) {
            failures.put(method + " " + path, new RestResponse(status, body));
        }

        void interruptOn(String method, String path) {
            interrupts.add(method + " " + path);
        }

        @Override
        public RestResponse send(String method, String path, String jsonBody, Duration timeout) throws IOException {
            calls.add(new Call(method, path, jsonBody));
            String key = method + " " + path;
            if (interrupts.contains(key)) {
                Thread.currentThread().interrupt();
            }
            if (Thread.currentThread().isInterrupted()) {
                // What an HttpClient send does on an already-interrupted thread.
                throw new IOException("interrupted");
            }
            RestResponse failure = failures.get(key);
            if (failure != null) {
                return failure;
            }
            if ("DELETE".equals(method) && path.startsWith("/rest/v0/vms/")) {
                destroyed.add(path.substring("/rest/v0/vms/".length()));
                return new RestResponse(204, "");
            }
            if (noContent && ("PATCH".equals(method) || "PUT".equals(method))) {
                // The documented answer to both the sizing PATCH and the tag PUT. create_vm is not one of
                // them: it answers 201 with the new VM's id, so blanking that here would test nothing
                // except this fixture.
                return new RestResponse(204, "");
            }
            if (path.startsWith("/rest/v0/vm-templates")) {
                return new RestResponse(200, templatesBody != null ? templatesBody : json(templates));
            }
            if (path.startsWith("/rest/v0/pools/") && path.contains("/actions/create_vm")) {
                return new RestResponse(201, "{\"id\":\"" + CLONE + "\"}");
            }
            if (path.startsWith("/rest/v0/pools")) {
                return new RestResponse(200, "[{\"id\":\"" + POOL + "\"}]");
            }
            if ("GET".equals(method) && path.startsWith("/rest/v0/vms/")) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("power_state", powerState);
                if (mainIpAddress != null) {
                    vm.put("mainIpAddress", mainIpAddress);
                }
                return new RestResponse(200, json(vm));
            }
            return new RestResponse(204, "");
        }

        List<String> paths() {
            return calls.stream().map(c -> c.method() + " " + c.path()).toList();
        }

        int indexOf(String method, String path) {
            int at = paths().indexOf(method + " " + path);
            assertTrue(at >= 0, method + " " + path + " was never called: " + paths());
            return at;
        }

        Call only(String method, String path) {
            List<Call> found = calls.stream()
                    .filter(c -> c.method().equals(method) && c.path().equals(path))
                    .toList();
            assertEquals(1, found.size(), "expected exactly one " + method + " " + path + " in " + paths());
            return found.get(0);
        }

        private static String json(Object value) {
            try {
                return M.writeValueAsString(value);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }
}
