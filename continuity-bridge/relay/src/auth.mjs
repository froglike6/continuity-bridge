import { createHash, timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";
import { failure } from "./errors.mjs";

const digest = (value) => createHash("sha256").update(value, "utf8").digest();

export class Authenticator {
  constructor(credentials) {
    this.credentials = credentials.map((credential) => Object.freeze({
      tokenDigest: digest(credential.token), role: credential.role, deviceId: credential.deviceId, revoked: credential.revoked === true,
    }));
  }

  authenticate(header) {
    if (typeof header !== "string" || !header.startsWith("Bearer ") || header.length <= 7) return failure("unauthorized");
    const candidate = digest(header.slice(7));
    let match;
    for (const credential of this.credentials) {
      if (credential.tokenDigest.length === candidate.length && timingSafeEqual(credential.tokenDigest, candidate)) match = credential;
    }
    if (match === undefined || match.revoked) return failure("unauthorized");
    return Object.freeze({ role: match.role, deviceId: match.deviceId });
  }

  static async fromFile(path) {
    const value = JSON.parse(await readFile(path, "utf8"));
    if (!Array.isArray(value.credentials) || value.credentials.some((entry) => typeof entry.token !== "string" ||
        !["android", "macos"].includes(entry.role) || typeof entry.deviceId !== "string" || entry.deviceId.length === 0)) {
      throw new TypeError("invalid auth configuration");
    }
    return new Authenticator(value.credentials);
  }
}
