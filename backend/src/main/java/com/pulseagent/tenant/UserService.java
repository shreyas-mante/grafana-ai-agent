package com.pulseagent.tenant;

import com.pulseagent.config.AppProperties;
import com.pulseagent.security.Hashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** People: sign-up, password login (bcrypt), workspace membership and invite links. */
@Service
public class UserService {

    public record User(String id, String email) {}

    public record Membership(String workspaceId, String role) {}

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);
    // Compared against when the email doesn't exist, so timing doesn't reveal which emails are registered.
    private static final String DUMMY_HASH = BCRYPT.encode("not-a-real-password-" + Crypto.randomToken(8));

    private final JdbcTemplate db;
    private final WorkspaceService workspaces;
    private final AuditService audit;
    private final AppProperties props;

    public UserService(JdbcTemplate db, WorkspaceService workspaces, AuditService audit, AppProperties props) {
        this.db = db;
        this.workspaces = workspaces;
        this.audit = audit;
        this.props = props;
    }

    public static String normalizeEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (e.length() > 254 || !e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("Enter a valid email address");
        return e;
    }

    public static void checkPassword(String p) {
        if (p == null || p.length() < 12) throw new IllegalArgumentException("Use at least 12 characters for your password");
        if (p.length() > 128) throw new IllegalArgumentException("Password is too long");
    }

    /** Creates an account. With an invite token it joins that workspace; otherwise it creates one. */
    @Transactional
    public Membership signup(String email, String password, String workspaceName, String inviteToken) {
        String e = normalizeEmail(email);
        checkPassword(password);
        String mode = props.platform().signup();
        Map<String, Object> invite = null;
        if (inviteToken != null && !inviteToken.isBlank()) {
            List<Map<String, Object>> rows = db.queryForList("select workspace_id, role from invites where token_hash = ? and used = false and expires_at > ?",
                    Hashing.sha256Hex(inviteToken), Timestamp.from(Instant.now()));
            if (rows.isEmpty()) throw new IllegalArgumentException("This invite link is invalid or has expired");
            invite = rows.get(0);
        } else if (!"open".equals(mode)) {
            throw new IllegalArgumentException("Sign-up needs an invite link");
        }
        Integer exists = db.queryForObject("select count(*) from users where email = ?", Integer.class, e);
        if (exists != null && exists > 0) throw new IllegalArgumentException("Couldn't create the account. If you already have one, sign in instead.");

        String userId = "usr_" + Crypto.randomToken(9).replaceAll("[^A-Za-z0-9]", "x");
        db.update("insert into users(id, email, password_hash, created_at) values (?,?,?,?)", userId, e, BCRYPT.encode(password), Timestamp.from(Instant.now()));
        String ws;
        String role;
        if (invite != null) {
            ws = (String) invite.get("workspace_id");
            role = (String) invite.get("role");
            db.update("update invites set used = true where token_hash = ?", Hashing.sha256Hex(inviteToken));
        } else {
            String name = workspaceName == null || workspaceName.isBlank() ? "My workspace" : workspaceName.trim();
            if (name.length() > 100 || name.matches(".*[\\p{Cntrl}<>].*")) throw new IllegalArgumentException("Choose a shorter workspace name");
            ws = workspaces.create(name, e);
            role = "OWNER";
        }
        db.update("insert into memberships(user_id, workspace_id, role, created_at) values (?,?,?,?)", userId, ws, role, Timestamp.from(Instant.now()));
        audit.log(ws, e, "member.joined", role);
        return new Membership(ws, role);
    }

    public Optional<User> authenticate(String email, String password) {
        String e;
        try {
            e = normalizeEmail(email);
        } catch (IllegalArgumentException ex) {
            BCRYPT.matches("x", DUMMY_HASH);
            return Optional.empty();
        }
        List<Map<String, Object>> rows = db.queryForList("select id, password_hash from users where email = ?", e);
        if (rows.isEmpty()) {
            BCRYPT.matches(password == null ? "" : password, DUMMY_HASH);
            return Optional.empty();
        }
        boolean ok = password != null && password.length() <= 128 && BCRYPT.matches(password, (String) rows.get(0).get("password_hash"));
        return ok ? Optional.of(new User((String) rows.get(0).get("id"), e)) : Optional.empty();
    }

    public Optional<String> email(String userId) {
        List<String> r = db.queryForList("select email from users where id = ?", String.class, userId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public Optional<String> role(String userId, String workspaceId) {
        List<String> r = db.queryForList("select role from memberships where user_id = ? and workspace_id = ?", String.class, userId, workspaceId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public Optional<String> firstWorkspace(String userId) {
        List<String> r = db.queryForList("select workspace_id from memberships where user_id = ? order by created_at limit 1", String.class, userId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public String createInvite(Actor by, String role) {
        if (!"OWNER".equals(role) && !"MEMBER".equals(role)) throw new IllegalArgumentException("Role must be OWNER or MEMBER");
        String token = Crypto.randomToken(24);
        db.update("insert into invites(token_hash, workspace_id, role, created_by, expires_at) values (?,?,?,?,?)",
                Hashing.sha256Hex(token), by.workspaceId(), role, by.display(), Timestamp.from(Instant.now().plus(Duration.ofDays(7))));
        audit.log(by, "invite.created", role);
        return token;
    }

    public List<Map<String, Object>> members(String ws) {
        return db.queryForList("select u.id, u.email, m.role, m.created_at from memberships m join users u on u.id = m.user_id where m.workspace_id = ? order by m.created_at", ws);
    }

    @Transactional
    public void removeMember(Actor by, String userId) {
        Integer owners = db.queryForObject("select count(*) from memberships where workspace_id = ? and role = 'OWNER' and user_id <> ?", Integer.class, by.workspaceId(), userId);
        if (owners == null || owners == 0) throw new IllegalArgumentException("A workspace needs at least one other owner first");
        if (db.update("delete from memberships where workspace_id = ? and user_id = ?", by.workspaceId(), userId) > 0) {
            audit.log(by, "member.removed", userId);
        }
    }

    @Transactional
    public void joinWithInvite(String userId, String inviteToken) {
        List<Map<String, Object>> rows = db.queryForList("select workspace_id, role from invites where token_hash = ? and used = false and expires_at > ?",
                Hashing.sha256Hex(inviteToken), Timestamp.from(Instant.now()));
        if (rows.isEmpty()) throw new IllegalArgumentException("This invite link is invalid or has expired");
        String ws = (String) rows.get(0).get("workspace_id");
        if (role(userId, ws).isPresent()) throw new IllegalArgumentException("You're already a member of that workspace");
        db.update("insert into memberships(user_id, workspace_id, role, created_at) values (?,?,?,?)", userId, ws, rows.get(0).get("role"), Timestamp.from(Instant.now()));
        db.update("update invites set used = true where token_hash = ?", Hashing.sha256Hex(inviteToken));
        audit.log(ws, email(userId).orElse(userId), "member.joined", String.valueOf(rows.get(0).get("role")));
    }
}
