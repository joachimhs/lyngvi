package no.syntakserror.lyngvi.gui.viewmodel;

import no.syntakserror.lyngvi.core.session.Conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * The full state of one running turn, kept per conversation so a view can leave and come back while the agent
 * works. The binder delivers full texts and full work lists, so every callback simply replaces the previous value.
 * Updated on the UI thread, like every other listener.
 */
public final class LiveTurn implements AgentRunBinder.Listener {

    public record Notice(String level, String text) {}

    private final String agent;
    private final String prompt;
    private final List<String> attachmentNames;
    private final long startedAt;
    private String model;
    private String text = "";
    private String reasoning = "";
    private List<Conversation.WorkItem> work = List.of();
    private final List<Notice> notices = new ArrayList<>();
    private Conversation.Usage usage;

    public LiveTurn(String agent, String prompt, List<String> attachmentNames, long startedAt) {
        this.agent = agent;
        this.prompt = prompt;
        this.attachmentNames = List.copyOf(attachmentNames);
        this.startedAt = startedAt;
    }

    public String agent() { return agent; }
    public String prompt() { return prompt; }
    public List<String> attachmentNames() { return attachmentNames; }
    public long startedAt() { return startedAt; }
    public String model() { return model; }
    public String text() { return text; }
    public String reasoning() { return reasoning; }
    public List<Conversation.WorkItem> work() { return work; }
    public List<Notice> notices() { return List.copyOf(notices); }
    public Conversation.Usage usage() { return usage; }

    /** The user message this turn answers, in the shape the transcript renders. */
    public Conversation.Message userMessage() {
        return new Conversation.Message("live-user", "user", startedAt, prompt, attachmentNames, List.of(), null, null);
    }

    @Override public void onStart(String agent, String model) { this.model = model; }
    @Override public void onText(String fullText) { text = fullText; }
    @Override public void onReasoning(String fullReasoning) { reasoning = fullReasoning; }
    @Override public void onWork(List<Conversation.WorkItem> items) { work = List.copyOf(items); }
    @Override public void onNotice(String level, String text) { notices.add(new Notice(level, text)); }
    @Override public void onUsage(Conversation.Usage usage) { this.usage = usage; }
}
