# HSTS — demo day

**Defense date: NEXT WEEK, exact date TBD (extension announced 2026-08-25; previously Wednesday 2026-08-27).** Group: **12-1**, prefix `G12-1`.

Everything that has to be true on the machines before the defence starts, in the order it has
to be done. Written to be executed, not read: every step is a box, and a box is only ticked
after the thing was actually observed on the machine it names.

Four checklists and a gate list:

| § | What | When | Who |
|---|---|---|---|
| [1](#1-build-the-jars-e201--e204b) | Confirm the group number, build the JARs, the Windows-build gate | the evening before, and again after the last commit | lead |
| [2](#2-clean-windows-machine-double-click-e202) | Double-click on a clean Windows machine | once, then after any packaging change | lead |
| [3](#3-fresh-machine-mysql--seeded-database-e206) | Fresh machine: MySQL to a seeded database | once per machine | lead |
| [4](#4-two-machine-lan-e205) | Two-machine LAN rehearsal | at least twice, once on the real demo network | all three |
| [5](#5-day-of-gates) | Day-of gates | the morning of | lead |

**Status of this document (E20).** The instructions are complete and reviewed. The manual
passes they describe (E20.2, E20.4b, E20.5, E20.6) have **not** been executed yet; their boxes
are deliberately empty and are ticked in `docs/TODO.md` only once somebody has run them on real
hardware.

**Timings.** Every "budget" below is an estimate for planning the evening. The **measured**
column is filled in during the first rehearsal, on the actual machines, and that number is the
one to plan the demo day around.

---

## 1. Build the JARs (E20.1 + E20.4b)

### 1.0 Confirm the group number first — before anything is named

- [ ] **CONFIRM the official group number in the course system before building anything named
      `G<Num>`.** We requested 13 after splitting from group 12, and the requested number is not
      automatically the registered number. Wrong `<Num>` means the wrong zip name and the wrong JAR
      names on the graded artifact
- [ ] The confirmed number is written down here, and every `G12-1` below is read as that number:
      **confirmed group number: ______**

`G12-1` is the example value throughout this document and the README. Nothing is hard-coded to it:
the number is a command-line switch (`-Djar.prefix=`), so confirming it late costs one rebuild and
one re-zip, and getting it wrong costs the cover of the submission.

### 1.1 The submission build is one line

```powershell
.\mvnw -B -Djar.prefix=G12-1 clean package
```

Substitute the real group number for `G12-1`. It produces exactly two artifacts:

```
target\G12-1_Server.jar
target\G12-1_Client.jar
```

Without the switch the same build produces `hsts-server.jar` / `hsts-client.jar`, which is what
every other document and habit in this repo refers to. The group number is never committed: the
switch activates a Maven profile, so there is no "remember to change it back" step and no risk of
a submission built from a stale edit.

Beside the JARs, `package` also leaves `server.properties` and `client.properties` in `target\`:
the machine's real files when the project root has them, otherwise copies of the
`*.properties.example` files. An existing file in `target\` is never overwritten, so a
`server.properties` that was edited to hold this machine's MySQL password survives a rebuild that
did not clean.

### 1.2 The Windows-build gate (E20.4b) — hard gate

**JavaFX natives are baked into the JAR at build time.** Shade packs whichever platform's JavaFX
artifacts the build machine resolved, and since E19 **both** JARs carry JavaFX (the server console
is a JavaFX window). A JAR built on Linux or macOS starts on Windows and dies with an
`UnsatisfiedLinkError` on the first window it tries to open. Nothing about the zip, the JRE or the
properties files can repair that.

- [ ] The final JARs were built **on Windows**, on the machine family that will run them
- [ ] They were built from the **final commit**, after the last code change of the day
- [ ] `target\` was cleaned first (`clean package`, not `package`), so no earlier JAR survived
- [ ] The two JARs' timestamps are minutes old, and match each other
- [ ] The submission zip was assembled **after** both of the above (see §5)

Sanity check, on the build machine, that the JARs contain Windows natives:

```powershell
# expect glass.dll / prism_d3d.dll and friends, NOT libglass.so
jar tf target\G12-1_Client.jar | Select-String "\.dll$" | Select-Object -First 5
jar tf target\G12-1_Server.jar | Select-String "\.dll$" | Select-Object -First 5
```

- [ ] Both listings show `.dll` files (a Linux build shows `.so`, a macOS build `.dylib`)

---

## 2. Clean Windows machine, double-click (E20.2)

**Packaging invariant (2026-08-24): the client jar ships the full project artifact, on purpose.**
No shade filters exclude server code, and none may be added: E6.11's editor calls
`QuestionValidator`/`BankMessages` across the tier (one rule, one home), and a "cleanup" that
filters server classes out of the client jar breaks the question editor on open in a way no test
on the full classpath can see. If jar size ever bothers anyone, the answer is phase 2, not a
filter. See ARCHITECTURE §packaging, same date.

The point of this pass is that nothing on the demo machine is left over from development: no
IDE, no Maven, no `JAVA_HOME` pointing at a project-local JDK, no `target\` directory full of
history. Use a machine (or a fresh Windows user account) that has never built this project.

**Budget: 20 minutes** · Measured: ______

### 2.1 Prepare the machine

- [ ] Windows 10/11, no IDE installed
- [ ] JDK or JRE 21 installed, and `java -version` in a **new** terminal prints 21
- [ ] `.jar` files are associated with the Java launcher. Check by right-clicking a JAR:
      "Open with" should offer "Java(TM) Platform SE binary". If it does not, associate it once
      with `%ProgramFiles%\Java\jdk-21\bin\javaw.exe`
- [ ] MySQL 8 installed and running (§3), and the database is seeded

### 2.2 Lay the files out the way the graders will

Copy **four** files into one folder, e.g. `C:\HSTS\`:

```
C:\HSTS\G12-1_Server.jar
C:\HSTS\G12-1_Client.jar
C:\HSTS\server.properties      (from target\, with this machine's MySQL password)
C:\HSTS\client.properties      (from target\)
```

Both JARs read their properties file **from beside the JAR first**, then from the working
directory, then from the copy bundled inside the JAR. Keeping all four files in one folder is
therefore the layout with the fewest ways to go wrong, and it is the layout to hand in.

### 2.3 Verify

- [ ] Double-click `G12-1_Server.jar`. The server console window opens within a few seconds
- [ ] The console header shows an address of the form `192.168.x.y:5555` and an `ID xxxx-xxxx`
- [ ] The log pane shows the Flyway and pool lines, ending with the listener started
- [ ] Double-click `G12-1_Client.jar`. The client window opens and goes to **Login**, showing
      "Connected to &lt;server name&gt; · change server"
- [ ] Sign in as `maya.levi` / `demo123` (the seed must be loaded, see §3)
- [ ] Close both windows. Neither leaves a java.exe running (check Task Manager)

If double-clicking does nothing at all, the association is wrong, not the JAR. Confirm with
`java -jar C:\HSTS\G12-1_Server.jar` from a terminal before changing anything else.

---

## 3. Fresh machine: MySQL to a seeded database (E20.6)

The claim this checklist proves: **a machine with nothing on it but Java and MySQL runs this
system without a single line of SQL being typed.** Flyway creates the database and the schema,
the console's seed button fills it.

| Step | Budget | Measured |
|---|---|---|
| 3.1 Install MySQL 8 (download + installer + first start) | 15 min | ______ |
| 3.2 Put the root password in `server.properties` | 2 min | ______ |
| 3.3 First server start: database created + 7 migrations | under 30 s | ______ |
| 3.4 Seed from the console button | under 30 s | ______ |
| **Total on a machine that already has Java 21** | **~20 min** | ______ |

### 3.1 Install MySQL 8

- [ ] MySQL Community Server 8.x installed (the "Server only" setup type is enough)
- [ ] During setup, choose **"Use Strong Password Encryption"** (the default) and set a root
      password you will not have to guess later
- [ ] Configure it as a **Windows service that starts automatically**, so a reboot on the day
      does not silently take the database away
- [ ] `Get-Service MySQL*` shows the service **Running**

Do **not** create a database. There is nothing to create by hand.

### 3.2 Point the server at it

Edit `server.properties` **beside the server JAR**:

```properties
db.user=root
db.password=<the password you just set>
```

- [ ] Saved beside the JAR, not only in the project root
- [ ] The file has no trailing spaces after the password (a properties file keeps them)

Optional, and only if the study bot will be demonstrated: add the provider keys in the same file
(`bot.deepseek.key`, `bot.anthropic.key`), or export `HSTS_DEEPSEEK_KEY` /
`HSTS_ANTHROPIC_KEY`, which win over the file. See §5.4.

### 3.3 First start creates everything

- [ ] Start the server (double-click, or `java -jar G12-1_Server.jar`)
- [ ] The log shows Flyway migrating **V1 to V7** against `hsts_db`
- [ ] It does **not** show "Unknown database" (the JDBC URL carries
      `createDatabaseIfNotExist=true`, so the first boot creates `hsts_db` itself)
- [ ] The console header appears and the listener starts

If the log shows a database failure instead, the server prints one sentence naming the likely
cause before the stack trace. Read the sentence:

| Sentence starts with | Means | Fix |
|---|---|---|
| "The database refused the login" | wrong `db.user` / `db.password` | fix `server.properties` beside the JAR (§3.2), restart |
| "MySQL is not answering on this machine" | service down or wrong port | start the MySQL service, restart |
| "The database was migrated by a different build" | leftover schema from another build | drop `hsts_db`, restart, let Flyway rebuild |

### 3.4 Seed

- [ ] In the server console, click **Load demo data if missing** (safe, inserts only what is missing)
- [ ] The result panel reports the rows loaded (18 users among them)
- [ ] Sign in from a client as `maya.levi` / `demo123` to prove the seed took

**Before every demo, reload rather than load.** The seed's exam windows are relative to load
time: one execution is "live right now", and a database seeded yesterday has no live exam today.
**Reload demo data** empties and reloads, and asks for confirmation first because it deletes everything.

- [ ] Reseeded on the morning of the demo, not the night before

Command-line equivalents, if the console is not up:

```powershell
java -cp G12-1_Server.jar server.db.seed.SeedMain            # load what is missing
java -cp G12-1_Server.jar server.db.seed.SeedMain --reseed   # empty and reload (asks first)
```

---

## 4. Two-machine LAN (E20.5)

Two laptops: **A** runs the server (and a client, so a demo survives B dying), **B** runs a
client. Everything below is done on the **real demo network** at least once, because the failure
this checklist exists to prevent is a university guest network that blocks broadcast.

**Budget: 30 minutes for the first rehearsal, 5 minutes to repeat** · Measured: ______

### 4.1 Ports

| Port | Protocol | Direction | What uses it |
|---|---|---|---|
| 5555 | TCP | inbound to A | the OCSF connection every client makes |
| 5556 | UDP | inbound to A | the discovery responder (F13.3): clients broadcast here, A answers |

Both are the defaults and both are switches (`--port`, `--discovery-port`), so a port clash on
the day is survivable: change it on A and type the address manually on B (§4.5).

### 4.2 Firewall on machine A (PowerShell, as Administrator)

```powershell
New-NetFirewallRule -DisplayName "HSTS server (TCP 5555)"    -Direction Inbound -Action Allow -Protocol TCP -LocalPort 5555 -Profile Any
New-NetFirewallRule -DisplayName "HSTS discovery (UDP 5556)" -Direction Inbound -Action Allow -Protocol UDP -LocalPort 5556 -Profile Any
```

- [ ] Both rules created, and `Get-NetFirewallRule -DisplayName "HSTS*"` lists them Enabled
- [ ] The network profile is **Private**, not Public. Windows blocks most inbound traffic on
      Public profiles regardless of the rules above:

```powershell
Get-NetConnectionProfile                                   # read the name and category
Set-NetConnectionProfile -Name "<name>" -NetworkCategory Private
```

- [ ] Unicast replies to broadcast are allowed (default on Windows, worth confirming once):

```powershell
netsh advfirewall show allprofiles | Select-String "Unicast"
```

The discovery reply is a **unicast UDP answer to a broadcast question**, so B needs that
behaviour, not an inbound rule of its own. If it is disabled by policy on a borrowed machine,
discovery fails and §4.5 is the path to use. That is exactly the case this rehearsal is for.

- [ ] After the demo, remove the rules: `Remove-NetFirewallRule -DisplayName "HSTS*"`

### 4.3 Same network, and read the address off the console

- [ ] A and B are on the **same subnet**: `ipconfig` on both, first three octets equal
- [ ] No VPN client is up on either machine (a VPN adapter routinely wins the default route and
      the console then shows an address B cannot reach)
- [ ] On A, the console header shows `<ip>:5555` in large type, with `ID xxxx-xxxx` beneath it
- [ ] That IP is one of the addresses `ipconfig` shows for the LAN adapter. If the console picked
      a virtual adapter (Hyper-V, VirtualBox, WSL), use the console's **address picker** to choose
      the right one, or type it into the manual override field
- [ ] Copy button puts exactly the header text on the clipboard, and it pastes into Notepad
- [ ] From B: `ping <A's ip>` answers, and `Test-NetConnection <A's ip> -Port 5555` reports
      `TcpTestSucceeded : True`

The last line is the one that separates "the firewall is wrong" from "the server is not
listening", and it takes five seconds. Do it before touching anything else.

### 4.4 The discovery path (the one the demo shows)

- [ ] On A, the console shows discovery **on** (the toggle is in the console; `--no-discovery`
      starts with it off)
- [ ] Start the client on B. It broadcasts, collects for about two seconds and finds A
- [ ] The picker lists A as `<name> · <ip>:5555 · <fingerprint>`
- [ ] Choosing it connects, pins it, and lands on **Login**
- [ ] Close and restart the client on B. It now **auto-connects** and goes straight to Login,
      showing "Connected to &lt;server&gt; · change server". No address is typed at any point
- [ ] Restart the **server**, then the client. The pin still matches, no warning appears

The mismatch dialog (a different server answering on a pinned address) is a rehearsed demo
moment, not an error to avoid. It is reproducible by deleting `server-id.properties` beside
`server.properties` on A and restarting: B then warns and asks for an explicit confirmation.

- [ ] Rehearsed once, and `server-id.properties` restored or the new id re-pinned afterwards

### 4.5 The manual-IP fallback (rehearse it, do not just read it)

Discovery is a convenience. Every demo must be completable without it.

- [ ] On B, click **change server** on the Login screen (or start with discovery off on A)
- [ ] Type A's IP and port 5555 by hand, connect, sign in
- [ ] The client remembers that endpoint: restart it and the address is pre-filled
- [ ] The same is achievable without touching the UI, by editing `client.properties` beside the
      client JAR before launch:

```properties
server.host=192.168.1.42
server.port=5555
```

- [ ] Verified that this file is read from **beside the JAR** (put it there, start the client,
      confirm the pre-filled address)

### 4.6 Hotspot fallback plan

If the venue network blocks broadcast **and** blocks or NATs client-to-client traffic, no
firewall rule on A can fix it. The plan is to stop using it.

- [ ] A phone with a mobile hotspot, charged, tethering tested **before** the day
- [ ] Both laptops join the hotspot, then re-run §4.3 (the IP changes, so read the console again)
- [ ] Or: Windows Mobile Hotspot on machine A itself, with B joining it. A then holds the
      hotspot's own address, which the console picker will offer
- [ ] An Ethernet cable plus a switch, if the room has one, as the third option
- [ ] Whichever is used, the smoke script (§4.7) is re-run on it. A network that carries a ping
      but not a broadcast still needs §4.5

### 4.7 Smoke script (the five minutes that prove the system)

Run in this order. Anything that fails here is a demo-blocking bug, not a curiosity.

- [ ] **Seed.** On A: server console → **Reload demo data** → confirm. The summary panel reports the rows
- [ ] **Login on A.** Client on A, sign in as `dana.cohen` / `demo123` (teacher). Land on the
      teacher home with her courses
- [ ] **Login on B.** Client on B, sign in as `maya.levi` / `demo123` (student). Land on the
      student home
- [ ] **Both connected.** The server console's connected-clients table shows **two** rows, with
      B's real IP and the two usernames
- [ ] **Exam join.** On B, open the exam that is live now (the seed always has one) and start it.
      The timer runs and questions render in Hebrew
- [ ] **Answer and submit** one question, and confirm the teacher side sees the attempt
- [ ] **Log stream.** On A's terminal, the coloured log shows the login, the join and the submit
      as they happen. This is the stream the defence watches (§5.3)
- [ ] **Disconnect.** Close the client on B; the console's client table loses its row

---

## 5. Day-of gates

Nothing below is optional and none of it is quick to fix in the room.

### 5.1 Stale JARs: the rule

**Only ever hand over JARs that came out of `target\` of the final commit.** Not a copy from
yesterday's folder, not the pair on the USB stick, not the ones already in the zip.

The failure is silent: a stale JAR starts, connects and demonstrates the *previous* build,
and the bug that was fixed last night reappears in front of the graders. There is no warning,
because a JAR carries no version.

- [ ] The group number was **confirmed in the course system**, not assumed (§1.0). Everything
      below carries it in its file name, so this is the first box for a reason
- [ ] `git status` is clean, and the last commit is the one being demonstrated
- [ ] `.\mvnw -B -Djar.prefix=G12-1 clean package` run **on Windows** (§1.2), from that commit
- [ ] Every copy of the JARs anywhere else (desktop, USB, downloads) deleted **before** copying
      the new ones out. Two files with the same name and different contents is the whole problem
- [ ] The demo machines run the JARs copied out **after** that build
- [ ] The submission zip `G12-1_Assignment3.zip` is assembled **LAST**, from those same two files
      plus the document, and its contents are listed once to confirm (E22.3)

Order, and it is the order for a reason: **commit → clean package on Windows → copy out →
rehearse on the demo machines → zip**. Rehearsing before the final build proves nothing about
what is being handed in, and zipping before the rehearsal means the zip holds an untested pair.

### 5.2 Rebuild, then rehearse

- [ ] After the last code change, the full `.\mvnw -B clean verify` is green (tests + coverage
      gate), not just `package`
- [ ] Then §1, §2 and §4.7 are re-run against the JARs from that build
- [ ] Any fix made after this point restarts this list. There is no "small change" exemption

### 5.3 The defence view

- [ ] The server is started **from a terminal** (Windows Terminal or PowerShell 5+), not only by
      double-click, so the coloured log stream is on screen: grey time, colour-coded level, cyan
      logger, plain message
- [ ] The terminal window is large and its font size is readable from the back of the room
- [ ] If the terminal prints escape codes such as `[0;39m` as text, it has no ANSI support.
      Open Windows Terminal instead. Never rebuild for this
- [ ] The server console window is also up (the address header, the client table, the log pane
      and the seed buttons are all demo material)

### 5.4 Study bot with live keys

- [ ] The E16.17 live-key checklist has been run **today**, in full:
      `docs/reports/lead/E16.md` §6. It covers both providers, the fallback path and the
      "no key" boot lines, and it takes about five minutes
- [ ] Keys are in `server.properties` **beside the JAR** on machine A, or exported as
      `HSTS_DEEPSEEK_KEY` / `HSTS_ANTHROPIC_KEY` (the environment wins)
- [ ] The keys are **not** in any file that goes into the submission zip
- [ ] If a provider is out of credit, the fallback is demonstrable rather than embarrassing:
      that is the ADR-009 story, and it is worth showing deliberately

### 5.5 The room

- [ ] Both laptops charged, and both chargers in the bag
- [ ] MySQL service set to start automatically on A, and A restarted once to prove it
- [ ] Backup laptop with Java 21, MySQL, the JARs and a seeded database, prepared the same way
- [ ] Phone hotspot charged and tested (§4.6)
- [ ] Demo accounts sheet to hand: `docs/DEMO_ACCOUNTS.md`, password `demo123`
- [ ] Reseeded this morning, so the live exam window is live now

---

## Quick answers

| Symptom | First thing to check |
|---|---|
| Client finds no server | Is discovery on in A's console? Is the network profile Private? (§4.2) |
| Client found it but cannot connect | `Test-NetConnection <ip> -Port 5555` from B (§4.3) |
| Console shows an address B cannot reach | A VPN or virtual adapter won. Use the address picker (§4.3) |
| Login fails for every account | The seed is not loaded. The message is generic by design (§3.4) |
| "Unknown database" | Not possible on a first boot; it means MySQL is refusing the login (§3.3) |
| Double-click does nothing | JAR association, not the JAR. Run it from a terminal to confirm (§2.3) |
| Terminal shows `[0;39m` | The terminal has no ANSI support. Use Windows Terminal (§5.3) |
| The fixed bug is back | A stale JAR is being run (§5.1) |
