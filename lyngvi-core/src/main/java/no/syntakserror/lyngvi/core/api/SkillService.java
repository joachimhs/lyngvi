package no.syntakserror.lyngvi.core.api;

import java.util.ArrayList;
import java.util.List;
import no.syntakserror.lyngvi.core.ask.AgentDescriptor;
import no.syntakserror.lyngvi.core.contract.SkillInfo;
import no.syntakserror.lyngvi.core.session.Locks;
import no.syntakserror.lyngvi.core.skills.SkillStore;
import no.syntakserror.lyngvi.core.skills.SkillSync;

/**
 * The skills of one sandbox: {@code skill list|show|edit|new|remove|enable|disable|sync}.
 *
 * <p>A skill is instructions the agent loads when it needs them, kept as a folder under
 * {@code <sandbox>/skills/} (spec §3.6). The sandbox is the whole scope: skills set up in one sandbox
 * directory are not offered in another, because they are files in that directory and nowhere else.
 *
 * <p>Every change ends with a sync, so the agents that take skills have the switched-on set in their
 * configuration directory before the next message — the directory is mounted into the session containers that
 * are already running, and the agent is started afresh for each message.
 */
public final class SkillService {

    private final Sandbox sandbox;

    SkillService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    private SkillStore store() {
        return new SkillStore(sandbox.paths());
    }

    public List<SkillInfo> list() {
        return store().list();
    }

    public SkillInfo show(String name) {
        return store().get(name);
    }

    /** The skill's {@code SKILL.md} as it is on disk. */
    public String read(String name) {
        return store().read(name);
    }

    /** Replaces the skill's {@code SKILL.md} with what the user wrote. */
    public SkillInfo save(String name, String content) {
        SkillInfo saved;
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            saved = store().write(name, content);
        }
        syncAll();
        return saved;
    }

    public SkillInfo create(String name) {
        SkillInfo created;
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            created = store().create(name);
        }
        syncAll();
        return created;
    }

    public SkillInfo remove(String name) {
        SkillInfo removed;
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            removed = store().remove(name);
        }
        syncAll();
        return removed;
    }

    public SkillInfo setEnabled(String name, boolean enabled) {
        SkillInfo changed;
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            changed = store().setEnabled(name, enabled);
        }
        syncAll();
        return changed;
    }

    /**
     * Brings one agent's skills directory in line with the switched-on skills. Under the sandbox lock: two
     * conversations sending their first message at the same time would otherwise write the same directory, and
     * the lock is re-entrant, so {@code rebuild-config} can call this while it holds it.
     */
    public SkillSync.Result sync(String agent) {
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            return new SkillSync(sandbox.paths()).sync(agent, store().enabled());
        }
    }

    /** The same for every agent the profile enables; what {@code rebuild-config} and the GUI call. */
    public List<SkillSync.Result> syncAll() {
        try (Locks.Held ignored = Locks.sandbox(sandbox.paths())) {
            List<SkillInfo> enabled = store().enabled();
            SkillSync sync = new SkillSync(sandbox.paths());
            List<SkillSync.Result> out = new ArrayList<>();
            for (String agent : sandbox.profile().agents().enabled()) out.add(sync.sync(agent, enabled));
            return out;
        }
    }

    /**
     * The enabled agents that take skills at all. The GUI names them on the page: an agent whose
     * {@code agent.json} declares no {@code skillsDir} never sees the sandbox's skills, and a page that lists
     * skills without saying so would promise more than it delivers.
     */
    public List<String> agentsTakingSkills() {
        List<String> out = new ArrayList<>();
        for (String agent : sandbox.profile().agents().enabled()) {
            AgentDescriptor.find(sandbox.paths().agents(), agent)
                    .filter(d -> d.skillsDir() != null && !d.skillsDir().isBlank())
                    .ifPresent(d -> out.add(d.label()));
        }
        return out;
    }
}
