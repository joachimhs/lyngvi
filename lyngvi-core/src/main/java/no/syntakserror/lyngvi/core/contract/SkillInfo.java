package no.syntakserror.lyngvi.core.contract;

/**
 * One skill in a sandbox, as {@code skill list} and the GUI show it.
 *
 * @param name        the folder name under {@code skills/}, which is also the name the agent invokes
 * @param description the {@code description:} line from the skill's front matter; it is what the agent reads to
 *                    decide whether the skill applies, so an empty one is reported as a problem
 * @param enabled     true when the skill is switched on for this sandbox and copied to the agents that take skills
 * @param problem     why the skill cannot be used, in English, or null when there is nothing wrong with it. A skill
 *                    with a problem is listed and can be edited — that is how it gets fixed — but never copied in.
 * @param path        the skill's folder on the host, for front-ends that offer to open it
 * @param extraFiles  files in the folder besides {@code SKILL.md} (references, scripts); they travel with the skill
 */
public record SkillInfo(String name, String description, boolean enabled, String problem, String path, int extraFiles) {

    public boolean valid() {
        return problem == null;
    }
}
