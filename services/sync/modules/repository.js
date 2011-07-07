/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Firefox Sync.
 *
 * The Initial Developer of the Original Code is
 * the Mozilla Foundation.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Philipp von Weitershausen <philipp@weitershausen.de>
 *   Richard Newman <rnewman@mozilla.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

const {classes: Cc, interfaces: Ci, utils: Cu, results: Cr} = Components;

Cu.import("resource://services-sync/log4moz.js");
Cu.import("resource://services-sync/rest.js");
Cu.import("resource://services-sync/util.js");

const EXPORTED_SYMBOLS = ["Repository",
                          "RepositorySession",
                          "Server11Repository",
                          "Crypto5Middleware"];

const DONE = { toString: function() "<DONE>" };
const STOP = { toString: function() "<STOP>" };

/**
 * Base repository.
 */
function Repository() {}
Repository.prototype = {

  /**
   * Values to pass to and from callbacks.
   */
  DONE: DONE,
  STOP: STOP,

  /**
   * Create a new session object.
   *
   * @param storeCallback
   *        Callback with the signature (error). It may be called multiple
   *        times with error objects. It will be always called with the DONE
   *        value when the store operation has been completed.
   *        @param error is an error object (where `error.guids` is an array
   *                     of the records' GUIDs that couldn't be stored and
   *                     `error.info` describes the error, e.g. an exception)
   *                     or the DONE value.
   *        @return STOP if the store session should be aborted.
   *
   * @param sessionCallback
   *        Callback with the signature (error, session). Invoked once a
   *        session object has been instantiated.
   *
   * @return an object which implements the RepositorySession interface.
   */
  createSession: function createSession(storeCallback, sessionCallback) {
    sessionCallback("Repository must implement 'createSession'");
  }
};

/**
 * A session for working with a Repository.
 *
 * TODO: can we make more than one of these at a time?
 */
function RepositorySession() {
}
RepositorySession.prototype = {


  /**
   * Retrieve a sequence of GUIDs corresponding to records that have been
   * modified since timestamp. The callback is invoked exactly once.
   *
   * @param timestamp
   *        Number of seconds since the epoch (can be a decimal number).
   * @param guidsCallback
   *        Callback function with the signature (error, guids_array).
   *        @param error is null for a successful operation.
   */
  guidsSince: function guidsSince(timestamp, guidsCallback) {
    throw "RepositorySession must implement 'guidsSince'";
  },

  /**
   * Retrieve a sequence of records that have been modified since timestamp.
   * Invoke the callback once for each retrieved record and finally with
   * the DONE value.
   *
   * @param timestamp
   *        Number of seconds since the epoch (can be a decimal number).
   * @param fetchCallback
   *        Callback function with the signature (error, record).
   *        @param error is null for a successful operation.
   *        @param record will be the DONE value on the last invocation.
   *        @return STOP if the fetch operation should be aborted.
   */
  fetchSince: function fetchSince(timestamp, fetchCallback) {
    throw "RepositorySession must implement 'fetchSince'";
  },

  /**
   * Retrieve a sequence of records by GUID. guids should be an iterable.
   * Invoke the callback once for each retrieved record and finally with
   * the DONE value.
   *
   * @param guids
   *        Array of GUIDs to retrieve.
   * @param fetchCallback
   *        Callback function with the signature (error, record).
   *        @param error is null for a succcessful operation.
   *        @param record will be the DONE value on the last invocation.
   *        @return STOP if the fetch operation should be aborted.
   */
  fetch: function fetch(guids, fetchCallback) {
    throw "RepositorySession must implement 'fetch'";
  },

  /**
   * Store an individual record in such a way that it won't be unnecessarily
   * returned by a fetch operation.
   * Implementations may choose to flush records to the data store in batches.
   * Callers must therefore call store with the DONE value after the last item.
   *
   * @param record
   *        A record to store, or the value DONE.
   */
  store: function store(record) {
    throw "RepositorySession must implement 'store'";
  },

  /**
   * Delete all items stored in the repository.
   */
  wipe: function wipe(wipeCallback) {
    throw "RepositorySession must implement 'wipe'";
  },

  /**
   * Perform any necessary cleanup, invoking callback when it's safe to
   * proceed.
   */
  dispose: function dispose(callback) {
    callback();
  },
};

//TODO question:
// how do we deal with http failures, like 400 (e.g. over quota), 401, 503, etc?
// probably best to decouple them from the synchronizer and notify the service
// or engine via observer notification directly from SyncStorageRequest.
// synchronizer probably only needs to know that it failed, not why.

/**
 * Sync 1.1 server repository
 *
 * Retrieves from and stores to a collection on an HTTP server that implements
 * the Sync 1.1 API.
 *
 * @param serverURI
 *        URI of the Sync 1.1 server (string)
 * @param username
 *        Username on the server (string)
 * @param collection
 *        Name of the collection (string)
 */
function Server11Repository(serverURI, username, collection) {
  Repository.call(this);

  if (serverURI[serverURI.length - 1] != "/") {
    serverURI += "/";
  }
  this.uri = serverURI + "1.1/" + username + "/storage/" + collection;
}
Server11Repository.prototype = {

  __proto__: Repository.prototype,

  /**
   * The complete URI (string) of the repository
   */
  uri: null,

  /**
   * TODO implement + document this
   */
  downloadLimit: null,

  createSession: function createSession(storeCallback, sessionCallback) {
    let session = new Server11Session(this, storeCallback);
    sessionCallback(null, session);
  }
};

/**
 * N.B., Server11Session does not currently implement the necessary
 * transactionality to be the second pair in a sync exchange: that is, if
 * stores are performed prior to reads, the reads will include records added by
 * the store operation.
 *
 * TODO: change this?
 */
function Server11Session(repository, storeCallback) {
  this.repository    = repository;
  this.storeCallback = storeCallback;
  this.batch         = [];   // Holds items until we have enough for a batch.
  this.flushQueue    = [];   // Holds completed batches to be flushed.

  let level = Svc.Prefs.get("log.logger.repository.server.store");
  this._log = Log4Moz.repository.getLogger("Sync.Server11Session");
  this._log.level = Log4Moz.Level[level];
}
Server11Session.prototype = {
  __proto__: RepositorySession.prototype,

  batch:         null,
  flushQueue:    null,
  repository:    null,
  storeCallback: null,

  /*
   * Flushing control.
   */
  done: false,
  flushing: 0,

  /**
   * Upload batch size.
   */
  batchSize: 100,

  /**
   * Session API.
   */
  guidsSince: function guidsSince(timestamp, guidsCallback) {
    let request = new SyncStorageRequest(this.repository.uri + "?newer=" + timestamp);
    request.get(function (error) {
      // Network error of sorts.
      if (error) {
        guidsCallback(error);
        return;
      }

      // HTTP error (could be a mis-configured server, wrong password, etc.)
      let response = request.response;
      if (response.status != 200) {
        guidsCallback(response);
        return;
      }

      // Convert the result to JSON. Invalid JSON is sadfaces.
      let result;
      try {
        result = JSON.parse(response.body);
      } catch (ex) {
        guidsCallback(ex);
        return;
      }
      guidsCallback(null, result);
    });
  },

  fetchSince: function fetchSince(timestamp, fetchCallback) {
    let uri = this.repository.uri + "?full=1&newer=" + timestamp;
    if (this.repository.downloadLimit) {
      uri += "&limit=" + this.repository.downloadLimit + "&sort=index";
    }
    this._fetchRecords(uri, fetchCallback);
  },

  fetch: function fetch(guids, fetchCallback) {
    let uri = this.repository.uri + "?full=1&ids=" + guids;
    this._fetchRecords(uri, fetchCallback);
  },

  createSession: function createSession(storeCallback, sessionCallback) {
    let session = new Server11Session(this, storeCallback);
    sessionCallback(null, session);
  },

  /**
   * Private stuff
   */

  _fetchRecords: function(uri, fetchCallback) {
    let request = new SyncStorageRequest(uri);
    request.setHeader("Accept", "application/newlines");

    function maybeAbort(rv) {
      if (rv == STOP) {
        request.abort();
        //XXX TODO should we call fetchCallback once more with DONE?
        // or should we assume that since fetchCallback returned STOP
        // that it knows it's not going to be called again ever.
      }
    }

    request.onProgress = function onProgress() {
      let response = request.response;
      if (!response.success) {
        request.abort();
        fetchCallback(response, DONE);
        return;
      }
      let newline;
      while ((newline = response.body.indexOf("\n")) > 0) {
        let json = response.body.slice(0, newline);
        response.body = response.body.slice(newline + 1);
        let record;
        try {
          record = JSON.parse(json);
        } catch(ex) {
          // Notify the caller of genuine parsing errors.
          maybeAbort(fetchCallback(ex));
          continue;
        }
        maybeAbort(fetchCallback(null, record));
      }
    };
    request.onComplete = function onComplete(error) {
      let response = request.response;
      // 'response.success' exposes nsIHttpChannel::requestSucceeded.
      if (error || response.success) {
        fetchCallback(error, DONE);
      } else {
        // We had an HTTP error, pass the HTTP response as the error.
        fetchCallback(response, DONE);
      }
    };
    request.get();
  },

  wipe: function wipe(wipeCallback) {
    let request = new SyncStorageRequest(this.repository.uri);
    request.delete(wipeCallback);
  },

  /*
   * All other invocations of store should have returned prior to store being
   * invoked with DONE. It is best if you invoke store serially.
   */
  store: function store(record) {
    // Ensure that we can't be finished more than once.
    if (this.done) {
      throw "Store session already marked as DONE.";
    }

    if (record != DONE) {
      this.batch.push(record);
      if (this.rollBatch(false)) {
        this.flush();
      }
      return;
    }

    this.done = true;
    this.rollBatch(true);
    this.flush();
  },

  /**
   * Private stuff.
   */

  /*
   * Work through the flush queue, flushing each batch. If an existing
   * flush is in progress, return. Ensure that if `done` is true, the
   * storeCallback is invoked once all items have been flushed.
   *
   * flush is invoked once per queued batch, and at least once (for DONE).
   *
   * Because store is specified to invoke the callback on error, rather than
   * aborting, we can flush each batch either in parallel or serially.
   */
  flush: function flush() {
    // Don't have more than one flush pending at once.
    if (this.flushing) {
      this._log.trace("Already flushing: returning.");
      return;
    }

    this.flushing = true;

    /**
     * Ensure that storeCallback is called with DONE when all of our batch
     * operations have completed, there are no more batches coming, and we've
     * been signaled.
     * This is safe to call repeatedly.
     */
    function finalmente() {
      this._log.trace("finalmente: " + this.flushing + ", " + this.flushQueue.length);
      this.flushing = false;

      if (this.flushQueue.length) {
        // There are outstanding batches, but nobody working on them. We should
        // kick off a flush.
        this._log.debug("Outstanding batches: scheduling flush.");
        Utils.nextTick(this.flush, this);
        return;
      }
      if (!this.done) {
        // We're not done yet, but we have nothing left to work on. Return
        // quietly; flush will be invoked again when the next batch arrives.
        this._log.trace("Not done: awaiting more data.");
        return;
      }
      if (!this.storeCallback) {
        // Uh oh. We're done, but the callback is gone. That should only happen
        // if we raced to the finish.
        this._log.warn("No store callback in flush!");
        return;
      }

      // Invoke the callback and prevent it being called again.
      this.storeCallback(DONE);
      this.storeCallback = null;
    }
    finalmente = finalmente.bind(this);

    //TODO should factor this helper out instead of redefining it all the time.
    function batchGUIDs(batch) {
      return [record.id for each (record in batch)];
    }

    this._log.debug("Flush queue length: " + this.flushQueue.length);

    // Finish up if we have an empty batch left.
    if (!this.flushQueue.length) {
      return finalmente();
    }

    let batch = this.flushQueue.pop();
    let request;
    try {
      request = new SyncStorageRequest(this.repository.uri);
    } catch (ex) {
      this.storeCallback({info: ex, guids: batchGUIDs(batch)});
      return finalmente();
    }

    request.post(batch, function onPost(error) {
      // Network error of sorts.
      if (error) {
        this.storeCallback({info: error, guids: batchGUIDs(batch)});
        return finalmente();
      }

      // HTTP error (could be a mis-configured server, over quota, etc.)
      // 'result.success' exposes nsIHttpChannel::requestSucceeded.
      if (!request.response.success) {
        this.storeCallback({info: request.response, guids: batchGUIDs(batch)});
        return finalmente();
      }

      // Analyze return value for whether some objects couldn't be saved.
      let resultObj;
      try {
        resultObj = JSON.parse(request.response.body);
      } catch (ex) {
        this._log.warn("Caught JSON parse exception: " + Utils.exceptionStr(ex));
        // Server return value did not parse as JSON. We must assume it's not
        // a valid implementation.
        this.storeCallback({info: ex, guids: batchGUIDs(batch)});
        return finalmente();
      }
      let failedIDs = Object.keys(resultObj.failed);
      if (failedIDs.length) {
        this.storeCallback({info: resultObj, guids: resultObj.failedIDs});
        return finalmente();
      }

      // TODO should we also process `resultObj.success` and verify it matches
      // all items in our batch?

      return finalmente();
    }.bind(this));
  },

  /*
   * Push the current batch into the queue for flushing, and
   * set us up for more items.
   * Returns true if a new batch was pushed.
   */
  rollBatch: function rollBatch(done) {
    let batch = this.batch;
    if (batch.length &&
        (batch.length == this.batchSize ||
         done)) {
      this.batch = [];
      this.flushQueue.push(batch);
      this._log.trace("Rolled batch.");
      return true;
    }
    this._log.trace("Not rolling batch.");
    return false;
  }
};


/**
 * Wraps a server repository to implement storage version 5 crypto.
 *
 * Transforms a local record to a WBO.
 */
function Crypto5Middleware(repository, keyBundle) {
  Repository.call(this);
  this.repository = repository;
  this.keyBundle = keyBundle;
}
Crypto5Middleware.prototype = {

  __proto__: Repository.prototype,

  /**
   * Repository API
   */

  createSession: function createSession(storeCallback, sessionCallback) {
    // Constructor takes care of invoking sessionCallback on our behalf.
    // TODO: do we have any GC issues here? Setting to this.session just in
    // case...
    this.session = new Crypto5StoreSession(this, storeCallback, sessionCallback);
  },

  /**
   * Crypto + storage format stuff
   */

  //XXX TODO this doesn't handle key refetches yet
  makeDecryptCb: function makeDecryptCb(fetchCallback) {
    return (function decryptCallback(error, record) {
      if (!error && record != DONE) {
        try {
          record = this.decrypt(record);
        } catch (ex) {
          record = null;
          error = ex;
        }
      }
      return fetchCallback(error, record);
    }).bind(this);
  },

  encrypt: function encrypt(record) {
    // 'sortindex' and 'ttl' are properties on the outer WBO.
    let sortindex = record.sortindex;
    let ttl = record.ttl;
    delete record.sortindex;
    delete record.ttl;

    let iv = Svc.Crypto.generateRandomIV();
    let ciphertext = Svc.Crypto.encrypt(JSON.stringify(record),
                                        this.keyBundle.encryptionKey, iv);
    let payload = {IV:         iv,
                   ciphertext: ciphertext,
                   hmac:       this.ciphertextHMAC(ciphertext)};
    return {id:        record.id,
            sortindex: sortindex,
            ttl:       ttl,
            payload:   JSON.stringify(payload)};
  },

  //XXX TODO this doesn't handle key refetches yet
  decrypt: function decrypt(wbo) {
    let payload = JSON.parse(wbo.payload);

    // Authenticate the encrypted blob with the expected HMAC
    let computedHMAC = this.ciphertextHMAC(payload.ciphertext);
    if (computedHMAC != payload.hmac) {
      Utils.throwHMACMismatch(payload.hmac, computedHMAC);
    }

    // Handle invalid data here. Elsewhere we assume that cleartext is an object.
    let cleartext = Svc.Crypto.decrypt(payload.ciphertext,
                                       this.keyBundle.encryptionKey,
                                       payload.IV);
    let record = JSON.parse(cleartext);

    // Verify that the outer WBO's id matches the inner record's id.
    if (record.id != wbo.id) {
      throw "Record id mismatch: " + record.id + " != " + wbo.id;
    }

    // Copy outer WBO attributes to inner record.
    record.modified = wbo.modified;
    record.sortindex = wbo.sortindex;
    record.ttl = wbo.ttl;
    return record;
  },

  ciphertextHMAC: function ciphertextHMAC(ciphertext) {
    let hasher = this.keyBundle.sha256HMACHasher;
    if (!hasher) {
      throw "Cannot compute HMAC without an HMAC key.";
    }
    return Utils.bytesAsHex(Utils.digestUTF8(ciphertext, hasher));
  }

};

function Crypto5StoreSession(repository, storeCallback, sessionCallback) {
  this.repository    = repository;
  this.storeCallback = storeCallback;
  this.session       = undefined;
  // TODO: do we need to wrap storeCallback at all?
  repository.repository.createSession(storeCallback,
    function (error, session) {
      this.session = session;
      sessionCallback(error, this);
    }.bind(this));
  return this;
}
Crypto5StoreSession.prototype = {
  __proto__: RepositorySession.prototype,

  repository: null,
  storeCallback: null,
  session: null,

  guidsSince: function guidsSince(timestamp, guidsCallback) {
    this.session.guidsSince(timestamp, guidsCallback);
  },

  fetchSince: function fetchSince(timestamp, fetchCallback) {
    this.session.fetchSince(timestamp, this.repository.makeDecryptCb(fetchCallback));
  },

  fetch: function fetch(guids, fetchCallback) {
    this.session.fetch(guids, this.repository.makeDecryptCb(fetchCallback));
  },

  store: function store(record) {
    if (record == DONE) {
      this.session.store(record);
      return;
    }

    let wbo;
    try {
      wbo = this.repository.encrypt(record);
    } catch (ex) {
      //TODO this feels weird and somewhat inefficient.
      this.storeCallback({exception: ex, guids: [record.id]});
      return;
    }
    this.session.store(wbo);
  },

  wipe: function wipe(wipeCallback) {
    this.session.wipe(wipeCallback);
  },

  dispose: function dispose(callback) {
    // Clean up GC hack.
    if (this.repository.session == this) {
      this.repository.session = undefined;
    }
    callback();
  }
};
