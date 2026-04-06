import { getApp, getApps, initializeApp } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js";
import { getAuth, onAuthStateChanged, signOut } from "https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js";

const signInLinks = Array.from(document.querySelectorAll("[data-auth-signin]"));
const userMenus = Array.from(document.querySelectorAll("[data-auth-user-menu]"));

if (signInLinks.length === 0 && userMenus.length === 0) {
  // Nothing to wire on this page.
} else {
  let authInstance = null;

  window.cryptoDashAuth = {
    getCurrentUser: () => {
      if (!authInstance) {
        return null;
      }
      return authInstance.currentUser;
    },
    getIdToken: async (forceRefresh) => {
      if (!authInstance || !authInstance.currentUser) {
        return "";
      }

      return authInstance.currentUser.getIdToken(Boolean(forceRefresh));
    }
  };

  function emitAuthState(user) {
    window.dispatchEvent(
      new CustomEvent("cryptodash-auth-state", {
        detail: {
          signedIn: Boolean(user),
          uid: user ? user.uid : "",
          email: user ? (user.email || "") : "",
          displayName: user ? (user.displayName || "") : ""
        }
      })
    );
  }

  function setDropdownVisible(menu, visible) {
    const trigger = menu.querySelector("[data-auth-user-trigger]");
    const dropdown = menu.querySelector("[data-auth-user-dropdown]");
    if (!trigger || !dropdown) return;

    trigger.setAttribute("aria-expanded", visible ? "true" : "false");
    dropdown.classList.toggle("d-none", !visible);
  }

  function hideAllDropdowns() {
    userMenus.forEach((menu) => setDropdownVisible(menu, false));
  }

  function setSignedOutState() {
    signInLinks.forEach((link) => link.classList.remove("d-none"));
    userMenus.forEach((menu) => {
      menu.classList.add("d-none");
      setDropdownVisible(menu, false);
    });
    emitAuthState(null);
  }

  function deriveDisplayName(user) {
    if (user && user.displayName && user.displayName.trim().length > 0) {
      return user.displayName.trim();
    }

    if (user && user.email && user.email.includes("@")) {
      return user.email.split("@")[0];
    }

    return "Profile";
  }

  function setSignedInState(user) {
    const displayName = deriveDisplayName(user);
    const email = (user && user.email) ? user.email : "No email";

    signInLinks.forEach((link) => link.classList.add("d-none"));

    userMenus.forEach((menu) => {
      menu.classList.remove("d-none");

      const compactName = menu.querySelector("[data-auth-user-name]");
      if (compactName) {
        compactName.textContent = displayName;
      }

      const fullName = menu.querySelector("[data-auth-user-name-full]");
      if (fullName) {
        fullName.textContent = displayName;
      }

      const emailNode = menu.querySelector("[data-auth-user-email]");
      if (emailNode) {
        emailNode.textContent = email;
      }
    });

    emitAuthState(user);
  }

  function bindDropdownEvents() {
    userMenus.forEach((menu) => {
      const trigger = menu.querySelector("[data-auth-user-trigger]");
      if (!trigger) return;

      trigger.addEventListener("click", (event) => {
        event.stopPropagation();
        const dropdown = menu.querySelector("[data-auth-user-dropdown]");
        const isVisible = dropdown && !dropdown.classList.contains("d-none");
        hideAllDropdowns();
        setDropdownVisible(menu, !isVisible);
      });
    });

    document.addEventListener("click", (event) => {
      const insideMenu = event.target.closest("[data-auth-user-menu]");
      if (!insideMenu) {
        hideAllDropdowns();
      }
    });

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        hideAllDropdowns();
      }
    });
  }

  function bindLogoutEvents() {
    const logoutButtons = Array.from(document.querySelectorAll("[data-auth-logout]"));

    logoutButtons.forEach((button) => {
      button.addEventListener("click", async () => {
        if (!authInstance) return;

        button.disabled = true;
        try {
          await signOut(authInstance);
          hideAllDropdowns();
          if (window.location.pathname.endsWith("/auth.html") || window.location.pathname.endsWith("auth.html")) {
            window.location.reload();
          }
        } catch (error) {
          console.error("Logout failed", error);
          alert("Could not log out. Please try again.");
        } finally {
          button.disabled = false;
        }
      });
    });
  }

  function ensureDeleteAccountButtons() {
    userMenus.forEach((menu) => {
      const dropdown = menu.querySelector("[data-auth-user-dropdown]");
      if (!dropdown) return;

      const existingButton = dropdown.querySelector("[data-auth-delete-account]");
      if (existingButton) return;

      const deleteButton = document.createElement("button");
      deleteButton.type = "button";
      deleteButton.className = "auth-dropdown-link text-danger";
      deleteButton.setAttribute("data-auth-delete-account", "true");
      deleteButton.textContent = "Delete Account";

      const logoutButton = dropdown.querySelector("[data-auth-logout]");
      if (logoutButton) {
        dropdown.insertBefore(deleteButton, logoutButton);
      } else {
        dropdown.appendChild(deleteButton);
      }
    });
  }

  async function deleteAccountOnServer() {
    if (!authInstance || !authInstance.currentUser) {
      throw new Error("You need to be signed in.");
    }

    const idToken = await authInstance.currentUser.getIdToken(true);
    const response = await fetch("/api/auth/delete-account", {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + idToken
      }
    });

    if (!response.ok) {
      let message = "Could not delete account.";
      try {
        const payload = await response.json();
        if (payload && payload.error) {
          message = String(payload.error);
        }
      } catch (error) {
        // Keep generic message.
      }

      throw new Error(message);
    }
  }

  function bindDeleteAccountEvents() {
    const deleteButtons = Array.from(document.querySelectorAll("[data-auth-delete-account]"));

    deleteButtons.forEach((button) => {
      button.addEventListener("click", async () => {
        if (!authInstance || !authInstance.currentUser) return;

        const accepted = window.confirm("Delete your account permanently? This will remove your profile and watchlist.");
        if (!accepted) {
          return;
        }

        button.disabled = true;
        try {
          await deleteAccountOnServer();
          await signOut(authInstance);
          hideAllDropdowns();
          window.location.href = "auth.html";
        } catch (error) {
          console.error("Delete account failed", error);
          alert(error.message || "Could not delete account. Please try again.");
        } finally {
          button.disabled = false;
        }
      });
    });
  }

  async function loadWebConfig() {
    const response = await fetch("/api/auth/web-config");
    if (!response.ok) {
      throw new Error("Failed to fetch auth web config.");
    }

    return response.json();
  }

  function getMissingConfigKeys(config) {
    const required = ["apiKey", "authDomain", "projectId", "appId"];
    return required.filter((key) => !config[key] || String(config[key]).trim().length === 0);
  }

  async function initializeAuthNav() {
    setSignedOutState();
    bindDropdownEvents();
    ensureDeleteAccountButtons();
    bindDeleteAccountEvents();
    bindLogoutEvents();

    try {
      const config = await loadWebConfig();
      const missing = getMissingConfigKeys(config);
      if (missing.length > 0) {
        // Keep signed out state if auth web config is not available.
        return;
      }

      const app = getApps().length > 0
        ? getApp()
        : initializeApp({
            apiKey: config.apiKey,
            authDomain: config.authDomain,
            projectId: config.projectId,
            appId: config.appId
          });

      authInstance = getAuth(app);

      onAuthStateChanged(authInstance, (user) => {
        if (user) {
          setSignedInState(user);
        } else {
          setSignedOutState();
        }
      });
    } catch (error) {
      console.error("Auth nav initialization failed", error);
      setSignedOutState();
    }
  }

  initializeAuthNav();
}
