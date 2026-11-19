# GLAFIRA Desktop Bridge — first owner pairing test

This is the current terminal-based Desktop Bridge test client, not the final Desktop graphical application.

## Roles

- Android GLAFIRA runs the TLS Bridge host and presents the one-time owner invitation.
- The Desktop Bridge CLI runs on the owner's laptop and initiates pairing with the Android host.
- The owner approves the Desktop device and the exact granted scopes on Android.
- The Desktop client imports the one-time credential bundle into an encrypted local vault.

ChatGPT is not a Bridge endpoint. Pairing Android to Desktop does not connect this app to the currently open ChatGPT conversation or copy ChatGPT account memory. The separate OpenAI API integration and shared project knowledge service are future architecture work.

## Windows launch

1. Install a Java 17 runtime on the laptop; verify with `java -version`.
2. Download and extract the `glafira-desktop-bridge-client` ZIP.
3. Open the extracted distribution's `bin` folder in PowerShell or Windows Terminal.
4. Run `.desktop-bridge.bat`.
5. Keep the terminal open. The client requires a real console because invitation, credential bundle and vault passphrase are entered through hidden console input.

## Pairing

1. On Android, open GLAFIRA Bridge Test and start the Bridge host/pairing flow.
2. Copy the one-time invitation JSON shown by Android.
3. On the Desktop CLI choose `1` (Pair this Desktop with Android).
4. Paste the invitation JSON when prompted (input is hidden).
5. Request the minimum scopes needed. For a basic read test: `project.read,conversation.read`. For the Desktop task worker later: `desktop.task.claim,desktop.task.result` as well.
6. Approve the pending Desktop device on Android and select the actual granted scopes. Requested scopes are hints only; Android owner approval is authoritative.
7. Copy the one-time credential bundle from Android into the Desktop CLI (hidden input).
8. Create a Desktop vault passphrase of at least 12 characters.
9. Return to Android and confirm credential transfer/acknowledgement so its temporary copy can be erased.

The Desktop vault is stored under the user's home directory at `~/.glafira-ai-hub/bridge/device.vault`. Do not share this file or passphrase. The Desktop queue is stored beside it.

## After pairing

- Choose `2` to use the imported credential for an authenticated read operation.
- Choose `3` to start the Desktop task worker. This requires owner-granted `desktop.task.claim` and `desktop.task.result` scopes and a local Ollama server at `127.0.0.1:11434`, with an installed model name supplied at the prompt.
- This worker currently runs registered local Ollama handlers. It is not yet the full autonomous programmer, does not connect to ChatGPT's current session, and does not automatically share ChatGPT memory.

## Security and recovery

- Pairing is owner-approved; the Desktop client does not grant itself scopes.
- Credentials are imported into the encrypted vault; hidden console input avoids echoing secrets.
- Use a strong unique vault passphrase and keep it separate from the one-time invitation.
- If the CLI reports an uncertain request, do not invent a new operation ID or resend changed payloads; follow the explicit recovery message.
- The validation client is a first integration step. Final Desktop UX, robust health monitoring and full Android↔Desktop end-to-end acceptance remain outstanding.
