# GLAFIRA AI HUB — Product Vision and Architecture

## Product definition

GLAFIRA AI HUB is a personal AI office for one owner/operator. It is not only a multi-model chat client. The owner works with a central coordinator (GLAFIRA), specialist AI agents, projects, tasks, documents, and connected business services.

The interaction style should preserve the compact, familiar ICQ 2003 / Windows XP visual language while the internal architecture remains modern, modular, secure, and maintainable.

## Product principles

1. **GLAFIRA is the primary coordinator.** The user can address GLAFIRA naturally; GLAFIRA can plan work, propose delegation, collect specialist results, and present a consolidated answer.
2. **The user remains in control.** Agents may analyze and prepare actions. External or irreversible actions (sending messages, changing business records, publishing content, spending money, deleting data) require explicit user approval unless a narrowly scoped automation rule has been configured.
3. **Project context is isolated.** Each project owns its tasks, documents, conversations, and memory. Agents receive only the context needed for their current task.
4. **Models are providers, not identities.** An agent role (for example, Analyst) is separate from the model provider (DeepSeek, OpenAI, GigaChat, etc.). The model can be changed without changing the agent's role or project history.
5. **Files are first-class project assets.** A file can be received, stored, previewed, attached to a task, and explicitly shared with an agent.
6. **Reliable before autonomous.** Every feature must have visible state, error handling, persistence, and a testable acceptance criterion before adding more autonomy.
7. **Secrets stay secret.** API keys must never be committed to Git or written into logs. A production design should use Android Keystore for local credentials and a secure backend for shared/team credentials and provider calls where appropriate.

## Main application areas

- **Contacts / Agents:** online state, agent role, provider, capabilities, and direct conversation.
- **Projects:** create, rename, archive, select, and manage project-specific context.
- **Tasks:** owner, assignee agent, status, priority, due date, input, output, and approval state.
- **Documents:** imported files, metadata, project association, previews, and explicit agent attachments.
- **Office / Council:** GLAFIRA coordinates a task among selected agents and produces a synthesis with attribution and disagreements.
- **Integrations:** provider credentials, GitHub, email, messaging, CRM/inventory, and other external services.
- **Settings and security:** account/device security, data export, retention, permissions, and automation approvals.

## Target architecture

Keep the current Android application buildable while gradually separating the existing large Compose screen and utility code into focused layers.

### Presentation layer
Compose screens and reusable ICQ-style components. Screens render state and send user intents; they should not perform network calls or file persistence directly.

### Domain layer
Pure Kotlin models and use cases:
- Project
- AgentProfile
- ModelProvider
- Conversation and Message
- Attachment
- Task and TaskRun
- CouncilSession
- ApprovalRequest
- IntegrationConnection

Domain rules should not depend on Android UI classes or a specific model provider.

### Data layer
Repositories and data sources:
- local persistence (initially device-local; migrate structured data to Room before the app becomes data-heavy)
- attachment storage and metadata
- provider clients
- integration clients
- synchronization and backup (later)

### Agent orchestration
A coordinator service should:
1. receive a user request in a project;
2. classify the task and identify relevant project context;
3. propose a plan and optional agent assignments;
4. request only the necessary specialist outputs;
5. track each subtask and handle failures/timeouts;
6. synthesize a result with clear attribution;
7. request approval before external side effects.

Agent-to-agent communication should be mediated by this orchestration layer, not by uncontrolled direct loops.

### Provider abstraction
Define a common provider interface for model requests. Provider-specific code handles endpoint, authentication, request/response mapping, model names, limits, and errors. Keep provider keys out of source control. DeepSeek should be integrated as a provider, not hard-coded as the identity or coordinator.

## Data ownership and isolation

- Every task, message, document, and council session belongs to a project.
- Every agent conversation is scoped to a project and agent.
- Attachments are stored with generated internal IDs; original file names are display metadata only.
- File paths are internal implementation details and should not be used as durable IDs.
- Deleting or archiving a project must define what happens to its tasks and files before implementation.
- Cross-project context sharing must be explicit and visible.

## Security and operational requirements

- Never store API keys in source files, BuildConfig committed values, or GitHub artifacts.
- Use least-privilege permissions for file sharing and integrations.
- Validate imported file size, MIME type, and destination name; handle duplicate names safely.
- Do not trust MIME type or file name supplied by another app.
- Show clear errors when a file cannot be imported or opened.
- Keep an audit trail for approved external actions.
- Treat model output and imported document content as untrusted input.

## Delivery roadmap

### Foundation — current priority
- Keep the Android app compiling and preserve the ICQ-inspired interface.
- Finish and device-test incoming file handling.
- Verify project selection and conversation persistence.
- Improve attachment handling: safe naming, duplicate handling, multi-file sharing, and visible failures.
- Add automated tests around project and message persistence.

### Projects and data foundation
- Move project/message/attachment persistence behind repositories.
- Introduce Room for structured data when migration is planned and tested.
- Add a document library scoped to projects.
- Add data export and backup before introducing cloud sync.

### Agent office
- Separate agent profiles from provider models.
- Add a secure provider abstraction and connect DeepSeek.
- Add GLAFIRA coordinator planning and explicit delegation.
- Add council sessions with progress, per-agent outputs, and final synthesis.

### Work management
- Add tasks, statuses, due dates, assignments, and approval requests.
- Link tasks to conversations and documents.
- Add notifications and a history of completed work.

### Integrations and automation
- Add integrations one at a time, each with clear permissions and a testable connection state.
- Start with developer workflow (GitHub), then selected business workflows.
- Require user approval for external side effects by default.
- Add narrowly scoped automation rules only after audit logging and recovery behavior exist.

## Definition of done for each feature

A feature is not complete merely because the APK compiles. It must have:
- a clear user-visible behavior;
- persistence and lifecycle handling where relevant;
- loading, empty, and error states;
- permission and security review;
- a reproducible manual test checklist;
- automated tests for important domain/data behavior;
- a successful CI build;
- device verification for Android-specific behavior.

## Immediate next engineering steps

1. Finish review and device verification of the incoming-file PR before merging it.
2. Extract project and message persistence behind small repository interfaces without changing current behavior.
3. Add attachment records with stable IDs and project ownership.
4. Add provider-neutral request/response contracts, then connect DeepSeek through the provider registry.
5. Implement the first real coordinator workflow: GLAFIRA creates a plan, asks one specialist agent for input, and returns a synthesized result. Keep user approval for all external actions.
