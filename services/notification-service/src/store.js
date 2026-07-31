/**
 * In-memory sent-message store for the FINIX notification demo.
 */
export class MessageStore {
  constructor() {
    this._messages = [];
    this._seq = 0;
  }

  add(partial) {
    this._seq += 1;
    const message = {
      id: `msg-${this._seq}`,
      sentAt: new Date().toISOString(),
      ...partial,
    };
    this._messages.push(message);
    return message;
  }

  list() {
    return [...this._messages];
  }

  clear() {
    this._messages = [];
    this._seq = 0;
  }
}
