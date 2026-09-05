import { Assertions, accepted, status } from "./assertions.mjs";
import { ack, event, request } from "./http-client.mjs";

export class FakeRoleClient {
  constructor(config, actor, epoch) {
    this.config = config;
    this.actor = actor;
    this.epoch = epoch;
    this.sequence = 0;
    this.applied = new Map();
  }

  async fetch(after = "0") {
    return request(this.config, { token: this.actor.token, path: `/v1/events?after=${after}&waitMs=0` });
  }

  async applyAndAck(assertions, expectedId) {
    const fetched = await this.fetch("0");
    status(assertions, "fake.fetch", fetched, 200);
    assertions.equal("fake.fetch.ids", fetched.body.events.map((entry) => entry.event.eventId), [expectedId]);
    const applied = fetched.body.events[0].event;
    this.applied.set(applied.eventId, { text: applied.payload.text, marker: applied.eventId });
    const response = await request(this.config, { token: this.actor.token, method: "POST", path: "/v1/acks",
      body: ack(this.actor, [applied.eventId]) });
    status(assertions, "fake.ack", response, 200);
    assertions.equal("fake.ack.ids", response.body.acked, [applied.eventId]);
    return this.applied.get(applied.eventId);
  }

  async capture(assertions, text, marker) {
    if (marker !== undefined && this.applied.get(marker)?.marker === marker && this.applied.get(marker)?.text === text) {
      this.applied.delete(marker);
      const health = await request(this.config);
      status(assertions, "fake.capture.marker.health", health, 200);
      return { published: false, tailCursor: health.body.tailCursor };
    }
    this.sequence += 1;
    const outbound = event(this.actor, { eventId: `task7-fake-${this.actor.role}-${this.sequence}`,
      originEpoch: this.epoch, sequence: this.sequence, payload: { text } });
    const response = await request(this.config, { token: this.actor.token, method: "POST", path: "/v1/events", body: outbound });
    accepted(assertions, "fake.capture.publish", response, 201, response.body.cursor);
    return { published: true, eventId: outbound.eventId, cursor: response.body.cursor };
  }
}
