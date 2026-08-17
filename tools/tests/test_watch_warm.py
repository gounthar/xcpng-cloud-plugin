"""Tests for watch_warm.py.

Nothing here talks to a controller or a pool. What is worth guarding is the parsing and the
change detection, because those are what turn a run into the timeline someone later quotes a
number out of. A watcher that mis-parses does not fail loudly; it prints a confident, wrong
history, which is how a healthy warm pool got reported as a stalled one.
"""

from fakes import vm_record

from watch_warm import changes, format_state, parse_agents, vm_states


def test_parse_agents_reads_every_reported_field():
    text = (
        "AGENT xcpng-a|online=true|accepting=true|idle=true|warm=true|reloaded=false\n"
        "AGENT xcpng-b|online=false|accepting=false|idle=true|warm=false|reloaded=true\n"
    )
    agents = parse_agents(text)
    assert set(agents) == {"xcpng-a", "xcpng-b"}
    assert agents["xcpng-a"]["warm"] == "true"
    assert agents["xcpng-b"]["reloaded"] == "true"


def test_parse_agents_ignores_lines_that_are_not_agents():
    text = "Result: [Lhudson.model.Computer;@783941e5\nAGENT xcpng-a|online=true\nnoise\n"
    assert list(parse_agents(text)) == ["xcpng-a"]


def test_a_field_the_probe_did_not_report_stays_absent():
    """The regression this tool exists in its current shape because of.

    The first version read acceptingTasks off /computer/api/json, which does not export it,
    and coerced the resulting None to False. That produced a 230-second log of a healthy warm
    spare apparently refusing work, and the plugin got investigated for a harness bug. An
    absent field must stay absent so a caller cannot mistake silence for a negative.
    """
    agents = parse_agents("AGENT xcpng-a|online=true|warm=true\n")
    assert "accepting" not in agents["xcpng-a"]
    assert agents["xcpng-a"].get("accepting") is not False


def test_format_state_puts_known_fields_in_a_stable_order():
    rendered = format_state({"reloaded": "false", "online": "true", "extra": "x"})
    assert rendered == "online=true reloaded=false extra=x"


def test_changes_reports_a_new_agent_a_changed_field_and_a_removal():
    previous = {"a": {"online": "false"}, "gone": {"online": "true"}}
    current = {"a": {"online": "true"}, "new": {"online": "false"}}
    assert changes(previous, current, "REMOVED") == [
        "a online=true",
        "new online=false",
        "gone REMOVED",
    ]


def test_changes_says_nothing_when_nothing_moved():
    state = {"a": {"online": "true"}}
    assert changes(state, dict(state), "REMOVED") == []


def test_first_observation_reports_everything_present():
    """previous=None is the post-restart case: the whole node list is news, not a no-op."""
    assert changes(None, {"a": {"online": "true"}}, "REMOVED") == ["a online=true"]


def test_vm_states_selects_on_the_owner_marker_only():
    records = {
        "ref1": vm_record("xcpng-agent-1", power="Running", owner="xcpng-lab"),
        "ref2": vm_record("someone-elses-vm", power="Running"),
    }
    assert vm_states(records) == {"xcpng-agent-1": "Running"}


def test_vm_states_excludes_templates_snapshots_and_dom0_even_when_marked():
    """A marked snapshot is an operator's restore point, and reporting it as an agent is how a
    filter that checks only one of the three flags gets someone's rollback destroyed."""
    records = {
        "ref1": vm_record("golden", template=True, owner="xcpng-lab"),
        "ref2": vm_record("agent-snap", snapshot=True, owner="xcpng-lab"),
        "ref3": vm_record("dom0", control_domain=True, owner="xcpng-lab"),
        "ref4": vm_record("xcpng-agent-1", power="Running", owner="xcpng-lab"),
    }
    assert vm_states(records) == {"xcpng-agent-1": "Running"}
