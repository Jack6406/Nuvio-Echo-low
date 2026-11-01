package com.nuvio.tv.core.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking

class LocalAuthWebServer(
    private val isSignedInProvider: () -> Boolean,
    private val signedInEmailProvider: () -> String?,
    private val onAuthenticate: suspend (email: String, password: String, isSignUp: Boolean) -> Result<Unit>,
    port: Int = 8098
) : NanoHTTPD(port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/api/status" -> serveStatus()
            session.method == Method.POST && session.uri == "/api/login" -> handleLogin(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            HTML_PAGE
        )
    }

    private fun serveStatus(): Response {
        val payload = mapOf(
            "signedIn" to isSignedInProvider(),
            "email" to (signedInEmailProvider() ?: "")
        )
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            gson.toJson(payload)
        )
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val bodyMap = HashMap<String, String>()
        try {
            session.parseBody(bodyMap)
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid request body")
        }
        val rawBody = bodyMap["postData"].orEmpty()
        val parsed: Map<String, Any> = try {
            gson.fromJson(rawBody, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        val email = (parsed["email"] as? String)?.trim().orEmpty()
        val password = (parsed["password"] as? String).orEmpty()
        val mode = (parsed["mode"] as? String)?.trim()?.lowercase().orEmpty()
        val isSignUp = mode == "signup"

        if (email.isBlank() || password.isBlank()) {
            return jsonError(Response.Status.BAD_REQUEST, "Please enter both email and password.")
        }

        val result = runBlocking {
            onAuthenticate(email, password, isSignUp)
        }

        return result.fold(
            onSuccess = {
                val okPayload = mapOf(
                    "ok" to true,
                    "email" to email
                )
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    gson.toJson(okPayload)
                )
            },
            onFailure = { error ->
                jsonError(
                    Response.Status.BAD_REQUEST,
                    error.message?.takeIf { it.isNotBlank() } ?: "Sign in failed. Check your email and password."
                )
            }
        )
    }

    private fun jsonError(status: Response.Status, message: String): Response {
        val payload = mapOf(
            "ok" to false,
            "error" to message
        )
        return newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            gson.toJson(payload)
        )
    }

    companion object {
        fun startOnAvailablePort(
            isSignedInProvider: () -> Boolean,
            signedInEmailProvider: () -> String?,
            onAuthenticate: suspend (email: String, password: String, isSignUp: Boolean) -> Result<Unit>,
            startPort: Int = 8098,
            maxAttempts: Int = 10
        ): LocalAuthWebServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = LocalAuthWebServer(
                        isSignedInProvider = isSignedInProvider,
                        signedInEmailProvider = signedInEmailProvider,
                        onAuthenticate = onAuthenticate,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (_: Exception) {
                }
            }
            return null
        }

        private const val HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Nuvio TV Sign In</title>
<style>
  :root {
    color-scheme: dark;
  }
  * {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
  }
  body {
    min-height: 100vh;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    background: radial-gradient(circle at 20% 20%, #241242 0%, #0d0718 55%, #050408 100%);
    color: #f5f7f8;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 20px;
  }
  .card {
    width: 100%;
    max-width: 420px;
    background: rgba(255, 255, 255, 0.045);
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 20px;
    padding: 32px 26px;
    box-shadow: 0 20px 50px rgba(0, 0, 0, 0.55);
    backdrop-filter: blur(12px);
  }
  .badge {
    display: inline-block;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: #b794f4;
    background: rgba(183, 148, 244, 0.14);
    border: 1px solid rgba(183, 148, 244, 0.28);
    padding: 5px 11px;
    border-radius: 999px;
    margin-bottom: 14px;
  }
  h1 {
    font-size: 26px;
    font-weight: 700;
    margin-bottom: 8px;
  }
  p.sub {
    font-size: 14px;
    line-height: 1.5;
    color: #969ca3;
    margin-bottom: 24px;
  }
  .tabs {
    display: flex;
    gap: 8px;
    background: rgba(255, 255, 255, 0.04);
    padding: 4px;
    border-radius: 12px;
    margin-bottom: 20px;
  }
  .tab {
    flex: 1;
    text-align: center;
    padding: 9px 12px;
    font-size: 14px;
    font-weight: 600;
    border-radius: 9px;
    cursor: pointer;
    color: #969ca3;
    border: none;
    background: transparent;
    transition: all 0.15s ease;
  }
  .tab.active {
    background: #ffffff;
    color: #090a0c;
  }
  .field {
    margin-bottom: 14px;
  }
  label {
    display: block;
    font-size: 13px;
    color: #cfd4da;
    margin-bottom: 6px;
    font-weight: 500;
  }
  input {
    width: 100%;
    padding: 13px 15px;
    font-size: 15px;
    color: #f5f7f8;
    background: rgba(0, 0, 0, 0.38);
    border: 1px solid rgba(255, 255, 255, 0.16);
    border-radius: 12px;
    outline: none;
  }
  input:focus {
    border-color: #ffffff;
  }
  button.submit {
    width: 100%;
    margin-top: 8px;
    padding: 14px;
    font-size: 15px;
    font-weight: 700;
    color: #050408;
    background: #ffffff;
    border: none;
    border-radius: 12px;
    cursor: pointer;
    transition: opacity 0.15s ease;
  }
  button.submit:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
  .status {
    margin-top: 16px;
    padding: 12px 14px;
    border-radius: 12px;
    font-size: 13.5px;
    line-height: 1.45;
    display: none;
  }
  .status.error {
    display: block;
    background: rgba(198, 40, 40, 0.22);
    border: 1px solid rgba(255, 110, 110, 0.35);
    color: #ff8a8a;
  }
  .status.success {
    display: block;
    background: rgba(46, 125, 50, 0.24);
    border: 1px solid rgba(124, 255, 155, 0.38);
    color: #7cff9b;
  }
</style>
</head>
<body>
  <div class="card">
    <div class="badge">Nuvio Fire TV</div>
    <h1>Sign in on your TV</h1>
    <p class="sub">Enter your Nuvio account email and password below to sign in directly on your Fire TV.</p>

    <div class="tabs">
      <button type="button" class="tab active" id="tabSignIn" onclick="setMode('signin')">Sign In</button>
      <button type="button" class="tab" id="tabSignUp" onclick="setMode('signup')">Create Account</button>
    </div>

    <form id="loginForm" onsubmit="submitLogin(event)">
      <div class="field">
        <label for="email">Email address</label>
        <input id="email" type="email" placeholder="you@example.com" required autocomplete="email" />
      </div>
      <div class="field">
        <label for="password">Password</label>
        <input id="password" type="password" placeholder="Enter your password" required autocomplete="current-password" />
      </div>
      <button type="submit" class="submit" id="submitBtn">Sign In on TV</button>
    </form>

    <div id="statusBox" class="status"></div>
  </div>

<script>
  var currentMode = 'signin';
  function setMode(mode) {
    currentMode = mode;
    document.getElementById('tabSignIn').className = mode === 'signin' ? 'tab active' : 'tab';
    document.getElementById('tabSignUp').className = mode === 'signup' ? 'tab active' : 'tab';
    document.getElementById('submitBtn').textContent = mode === 'signin' ? 'Sign In on TV' : 'Create Account & Sign In';
    var statusBox = document.getElementById('statusBox');
    statusBox.className = 'status';
    statusBox.textContent = '';
  }

  async function submitLogin(e) {
    e.preventDefault();
    var email = document.getElementById('email').value.trim();
    var password = document.getElementById('password').value;
    var btn = document.getElementById('submitBtn');
    var statusBox = document.getElementById('statusBox');

    if (!email || !password) return;

    btn.disabled = true;
    btn.textContent = currentMode === 'signin' ? 'Signing in on TV...' : 'Creating account...';
    statusBox.className = 'status';
    statusBox.textContent = '';

    try {
      var res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email, password: password, mode: currentMode })
      });
      var data = await res.json();
      if (res.ok && data.ok) {
        statusBox.className = 'status success';
        statusBox.textContent = 'Signed in as ' + (data.email || email) + '! Your Fire TV is now syncing your account.';
        btn.textContent = 'Connected!';
      } else {
        statusBox.className = 'status error';
        statusBox.textContent = data.error || 'Sign in failed. Please check your credentials.';
        btn.disabled = false;
        btn.textContent = currentMode === 'signin' ? 'Sign In on TV' : 'Create Account & Sign In';
      }
    } catch (err) {
      statusBox.className = 'status error';
      statusBox.textContent = 'Could not reach Fire TV. Make sure your computer/phone is on the same Wi-Fi network.';
      btn.disabled = false;
      btn.textContent = currentMode === 'signin' ? 'Sign In on TV' : 'Create Account & Sign In';
    }
  }
</script>
</body>
</html>"""
    }
}
