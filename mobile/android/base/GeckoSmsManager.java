/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.ArrayList;

/**
 * This class is returning unique ids for PendingIntent requestCode attribute.
 * There are only |Integer.MAX_VALUE - Integer.MIN_VALUE| unique IDs available,
 * and they wrap around.
 */
class PendingIntentUID
{
  static private int sUID = Integer.MIN_VALUE;

  static public int generate() { return sUID++; }
}

/**
 * The envelope class contains all information that are needed to keep track of
 * a sent SMS.
 */
class Envelope
{
  enum SubParts {
    SENT_PART,
    DELIVERED_PART
  }

  protected int       mId;
  protected int       mMessageId;
  protected long      mMessageTimestamp;

  /**
   * Number of sent/delivered remaining parts.
   * @note The array has much slots as SubParts items.
   */
  protected int[]     mRemainingParts;

  /**
   * Whether sending/delivering is currently failing.
   * @note The array has much slots as SubParts items.
   */
  protected boolean[] mFailing;

  /**
   * Error type (only for sent).
   */
  protected int       mError;

  public Envelope(int aId, int aParts) {
    mId = aId;
    mMessageId = -1;
    mMessageTimestamp = 0;
    mError = GeckoSmsManager.kNoError;

    int size = Envelope.SubParts.values().length;
    mRemainingParts = new int[size];
    mFailing = new boolean[size];

    for (int i=0; i<size; ++i) {
      mRemainingParts[i] = aParts;
      mFailing[i] = false;
    }
  }

  public void decreaseRemainingParts(Envelope.SubParts aType) {
    --mRemainingParts[aType.ordinal()];

    if (mRemainingParts[SubParts.SENT_PART.ordinal()] >
        mRemainingParts[SubParts.DELIVERED_PART.ordinal()]) {
      Log.e("GeckoSmsManager", "Delivered more parts than we sent!?");
    }
  }

  public boolean arePartsRemaining(Envelope.SubParts aType) {
    return mRemainingParts[aType.ordinal()] != 0;
  }

  public void markAsFailed(Envelope.SubParts aType) {
    mFailing[aType.ordinal()] = true;
  }

  public boolean isFailing(Envelope.SubParts aType) {
    return mFailing[aType.ordinal()];
  }

  public int getMessageId() {
    return mMessageId;
  }

  public void setMessageId(int aMessageId) {
    mMessageId = aMessageId;
  }

  public long getMessageTimestamp() {
    return mMessageTimestamp;
  }

  public void setMessageTimestamp(long aMessageTimestamp) {
    mMessageTimestamp = aMessageTimestamp;
  }

  public int getError() {
    return mError;
  }

  public void setError(int aError) {
    mError = aError;
  }
}

/**
 * Postman class is a singleton that manages Envelope instances.
 */
class Postman
{
  public static final int kUnknownEnvelopeId = -1;

  private static final Postman sInstance = new Postman();

  private ArrayList<Envelope> mEnvelopes = new ArrayList<Envelope>(1);

  private Postman() {}

  public static Postman getInstance() {
    return sInstance;
  }

  public int createEnvelope(int aParts) {
    /*
     * We are going to create the envelope in the first empty slot in the array
     * list. If there is no empty slot, we create a new one.
     */
    int size = mEnvelopes.size();

    for (int i=0; i<size; ++i) {
      if (mEnvelopes.get(i) == null) {
        mEnvelopes.set(i, new Envelope(i, aParts));
        return i;
      }
    }

    mEnvelopes.add(new Envelope(size, aParts));
    return size;
  }

  public Envelope getEnvelope(int aId) {
    if (aId < 0 || mEnvelopes.size() <= aId) {
      Log.e("GeckoSmsManager", "Trying to get an unknown Envelope!");
      return null;
    }

    Envelope envelope = mEnvelopes.get(aId);
    if (envelope == null) {
      Log.e("GeckoSmsManager", "Trying to get an empty Envelope!");
    }

    return envelope;
  }

  public void destroyEnvelope(int aId) {
    if (aId < 0 || mEnvelopes.size() <= aId) {
      Log.e("GeckoSmsManager", "Trying to destroy an unknown Envelope!");
      return;
    }

    if (mEnvelopes.set(aId, null) == null) {
      Log.e("GeckoSmsManager", "Trying to destroy an empty Envelope!");
    }
  }
}

class SmsIOThread extends Thread {
  private final static SmsIOThread sInstance = new SmsIOThread();

  private Handler mHandler;

  public static SmsIOThread getInstance() {
    return sInstance;
  }

  public boolean execute(Runnable r) {
    return mHandler.post(r);
  }

  public void run() {
    Looper.prepare();

    mHandler = new Handler();

    Looper.loop();
  }
}

class MessagesListManager
{
  private static final MessagesListManager sInstance = new MessagesListManager();

  public static MessagesListManager getInstance() {
    return sInstance;
  }

  private ArrayList<Cursor> mCursors = new ArrayList<Cursor>(0);

  public int add(Cursor aCursor) {
    int size = mCursors.size();

    for (int i=0; i<size; ++i) {
      if (mCursors.get(i) == null) {
        mCursors.set(i, aCursor);
        return i;
      }
    }

    mCursors.add(aCursor);
    return size;
  }

  public Cursor get(int aId) {
    if (aId < 0 || mCursors.size() <= aId) {
      Log.e("GeckoSmsManager", "Trying to get an unknown list!");
      return null;
    }

    Cursor cursor = mCursors.get(aId);
    if (cursor == null) {
      Log.e("GeckoSmsManager", "Trying to get an empty list!");
    }

    return cursor;
  }

  public void remove(int aId) {
    if (aId < 0 || mCursors.size() <= aId) {
      Log.e("GeckoSmsManager", "Trying to destroy an unknown list!");
      return;
    }

    Cursor cursor = mCursors.set(aId, null);
    if (cursor == null) {
      Log.e("GeckoSmsManager", "Trying to destroy an empty list!");
      return;
    }

    cursor.close();
  }

  public void clear() {
    for (int i=0; i<mCursors.size(); ++i) {
      Cursor c = mCursors.get(i);
      if (c != null) {
        c.close();
      }
    }

    mCursors.clear();
  }
}

public class GeckoSmsManager
  extends BroadcastReceiver
  implements ISmsManager
{
  public final static String ACTION_SMS_RECEIVED  = "android.provider.Telephony.SMS_RECEIVED";
  public final static String ACTION_SMS_SENT      = "org.mozilla.gecko.SMS_SENT";
  public final static String ACTION_SMS_DELIVERED = "org.mozilla.gecko.SMS_DELIVERED";

  /*
   * Make sure that the following error codes are in sync with |ErrorType| in:
   * dom/sms/src/Types.h
   * The error code are owned by the DOM.
   */
  public final static int kNoError       = 0;
  public final static int kNoSignalError = 1;
  public final static int kNotFoundError = 2;
  public final static int kUnknownError  = 3;
  public final static int kInternalError = 4;

  private final static int kMaxMessageSize    = 160;

  private final static Uri kSmsContentUri     = Uri.parse("content://sms");
  private final static Uri kSmsSentContentUri = Uri.parse("content://sms/sent");

  private final static int kSmsTypeInbox      = 1;
  private final static int kSmsTypeSentbox    = 2;

  /*
   * Keep the following error codes in syng with |DeliveryState| in:
   * dom/sms/src/Types.h
   */
  private final static int kDeliveryStateSent     = 0;
  private final static int kDeliveryStateReceived = 1;
  private final static int kDeliveryStateUnknown  = 2;
  private final static int kDeliveryStateEndGuard = 3;

  private final static String[] kRequiredMessageRows = new String[] { "_id", "address", "body", "date", "type" };

  public GeckoSmsManager() {
    SmsIOThread.getInstance().start();
  }

  public void start() {
    IntentFilter smsFilter = new IntentFilter();
    smsFilter.addAction(GeckoSmsManager.ACTION_SMS_RECEIVED);
    smsFilter.addAction(GeckoSmsManager.ACTION_SMS_SENT);
    smsFilter.addAction(GeckoSmsManager.ACTION_SMS_DELIVERED);

    GeckoApp.mAppContext.registerReceiver(this, smsFilter);
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent.getAction().equals(ACTION_SMS_RECEIVED)) {
      // TODO: Try to find the receiver number to be able to populate
      //       SmsMessage.receiver.
      // TODO: Get the id and the date from the stock app saved message.
      //       Using the stock app saved message require us to wait for it to
      //       be saved which can lead to race conditions.

      Bundle bundle = intent.getExtras();

      if (bundle == null) {
        return;
      }

      Object[] pdus = (Object[]) bundle.get("pdus");

      for (int i=0; i<pdus.length; ++i) {
        SmsMessage msg = SmsMessage.createFromPdu((byte[])pdus[i]);

        GeckoAppShell.notifySmsReceived(msg.getDisplayOriginatingAddress(),
                                        msg.getDisplayMessageBody(),
                                        System.currentTimeMillis());
      }

      return;
    }

    if (intent.getAction().equals(ACTION_SMS_SENT) ||
        intent.getAction().equals(ACTION_SMS_DELIVERED)) {
      Bundle bundle = intent.getExtras();

      if (bundle == null || !bundle.containsKey("envelopeId") ||
          !bundle.containsKey("number") || !bundle.containsKey("message") ||
          !bundle.containsKey("requestId") || !bundle.containsKey("processId")) {
        Log.e("GeckoSmsManager", "Got an invalid ACTION_SMS_SENT/ACTION_SMS_DELIVERED!");
        return;
      }

      int envelopeId = bundle.getInt("envelopeId");
      Postman postman = Postman.getInstance();

      Envelope envelope = postman.getEnvelope(envelopeId);
      if (envelope == null) {
        Log.e("GeckoSmsManager", "Got an invalid envelope id (or Envelope has been destroyed)!");
        return;
      }

      Envelope.SubParts part = intent.getAction().equals(ACTION_SMS_SENT)
                                 ? Envelope.SubParts.SENT_PART
                                 : Envelope.SubParts.DELIVERED_PART;
      envelope.decreaseRemainingParts(part);
 

      if (getResultCode() != Activity.RESULT_OK) {
        switch (getResultCode()) {
          case SmsManager.RESULT_ERROR_NULL_PDU:
            envelope.setError(kInternalError);
            break;
          case SmsManager.RESULT_ERROR_NO_SERVICE:
          case SmsManager.RESULT_ERROR_RADIO_OFF:
            envelope.setError(kNoSignalError);
            break;
          case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
          default:
            envelope.setError(kUnknownError);
            break;
        }
        envelope.markAsFailed(part);
        Log.i("GeckoSmsManager", "SMS part sending failed!");
      }

      if (envelope.arePartsRemaining(part)) {
        return;
      }

      if (envelope.isFailing(part)) {
        if (part == Envelope.SubParts.SENT_PART) {
          GeckoAppShell.notifySmsSendFailed(envelope.getError(),
                                            bundle.getInt("requestId"),
                                            bundle.getLong("processId"));
          Log.i("GeckoSmsManager", "SMS sending failed!");
        } else {
          // It seems unlikely to get a result code for a failure to deliver.
          // Even if, we don't want to do anything with this.
          Log.e("GeckoSmsManager", "SMS failed to be delivered... is that even possible?");
        }
      } else {
        if (part == Envelope.SubParts.SENT_PART) {
          String number = bundle.getString("number");
          String message = bundle.getString("message");
          long timestamp = System.currentTimeMillis();

          int id = GeckoAppShell.saveMessageInSentbox(number, message, timestamp);

          GeckoAppShell.notifySmsSent(id, number, message, timestamp,
                                      bundle.getInt("requestId"),
                                      bundle.getLong("processId"));

          envelope.setMessageId(id);
          envelope.setMessageTimestamp(timestamp);

          Log.i("GeckoSmsManager", "SMS sending was successfull!");
        } else {
          GeckoAppShell.notifySmsDelivered(envelope.getMessageId(),
                                           bundle.getString("number"),
                                           bundle.getString("message"),
                                           envelope.getMessageTimestamp());
          Log.i("GeckoSmsManager", "SMS succesfully delivered!");
        }
      }

      // Destroy the envelope object only if the SMS has been sent and delivered.
      if (!envelope.arePartsRemaining(Envelope.SubParts.SENT_PART) &&
          !envelope.arePartsRemaining(Envelope.SubParts.DELIVERED_PART)) {
        postman.destroyEnvelope(envelopeId);
      }

      return;
    }
  }

  public int getNumberOfMessagesForText(String aText) {
    return SmsManager.getDefault().divideMessage(aText).size();
  }

  public void send(String aNumber, String aMessage, int aRequestId, long aProcessId) {
    int envelopeId = Postman.kUnknownEnvelopeId;

    try {
      SmsManager sm = SmsManager.getDefault();

      Intent sentIntent = new Intent(ACTION_SMS_SENT);
      Intent deliveredIntent = new Intent(ACTION_SMS_DELIVERED);

      Bundle bundle = new Bundle();
      bundle.putString("number", aNumber);
      bundle.putString("message", aMessage);
      bundle.putInt("requestId", aRequestId);
      bundle.putLong("processId", aProcessId);

      if (aMessage.length() <= kMaxMessageSize) {
        envelopeId = Postman.getInstance().createEnvelope(1);
        bundle.putInt("envelopeId", envelopeId);

        sentIntent.putExtras(bundle);
        deliveredIntent.putExtras(bundle);

        /*
         * There are a few things to know about getBroadcast and pending intents:
         * - the pending intents are in a shared pool maintained by the system;
         * - each pending intent is identified by a token;
         * - when a new pending intent is created, if it has the same token as
         *   another intent in the pool, one of them has to be removed.
         *
         * To prevent having a hard time because of this situation, we give a
         * unique id to all pending intents we are creating. This unique id is
         * generated by GetPendingIntentUID().
         */
        PendingIntent sentPendingIntent =
          PendingIntent.getBroadcast(GeckoApp.mAppContext,
                                     PendingIntentUID.generate(), sentIntent,
                                     PendingIntent.FLAG_CANCEL_CURRENT);

        PendingIntent deliveredPendingIntent =
          PendingIntent.getBroadcast(GeckoApp.mAppContext,
                                     PendingIntentUID.generate(), deliveredIntent,
                                     PendingIntent.FLAG_CANCEL_CURRENT);

        sm.sendTextMessage(aNumber, "", aMessage,
                           sentPendingIntent, deliveredPendingIntent);
      } else {
        ArrayList<String> parts = sm.divideMessage(aMessage);
        envelopeId = Postman.getInstance().createEnvelope(parts.size());
        bundle.putInt("envelopeId", envelopeId);

        sentIntent.putExtras(bundle);
        deliveredIntent.putExtras(bundle);

        ArrayList<PendingIntent> sentPendingIntents =
          new ArrayList<PendingIntent>(parts.size());
        ArrayList<PendingIntent> deliveredPendingIntents =
          new ArrayList<PendingIntent>(parts.size());

        for (int i=0; i<parts.size(); ++i) {
          sentPendingIntents.add(
            PendingIntent.getBroadcast(GeckoApp.mAppContext,
                                       PendingIntentUID.generate(), sentIntent,
                                       PendingIntent.FLAG_CANCEL_CURRENT)
          );

          deliveredPendingIntents.add(
            PendingIntent.getBroadcast(GeckoApp.mAppContext,
                                       PendingIntentUID.generate(), deliveredIntent,
                                       PendingIntent.FLAG_CANCEL_CURRENT)
          );
        }

        sm.sendMultipartTextMessage(aNumber, "", parts, sentPendingIntents,
                                    deliveredPendingIntents);
      }
    } catch (Exception e) {
      Log.e("GeckoSmsManager", "Failed to send an SMS: ", e);

      if (envelopeId != Postman.kUnknownEnvelopeId) {
        Postman.getInstance().destroyEnvelope(envelopeId);
      }

      GeckoAppShell.notifySmsSendFailed(kUnknownError, aRequestId, aProcessId);
    }
  }

  public int saveSentMessage(String aRecipient, String aBody, long aDate) {
    class IdTooHighException extends Exception { }

    try {
      ContentValues values = new ContentValues();
      values.put("address", aRecipient);
      values.put("body", aBody);
      values.put("date", aDate);

      ContentResolver cr = GeckoApp.mAppContext.getContentResolver();
      Uri uri = cr.insert(kSmsSentContentUri, values);

      long id = ContentUris.parseId(uri);

      // The DOM API takes a 32bits unsigned int for the id. It's unlikely that
      // we happen to need more than that but it doesn't cost to check.
      if (id > Integer.MAX_VALUE) {
        throw new IdTooHighException();
      }

      return (int)id;
    } catch (IdTooHighException e) {
      Log.e("GeckoSmsManager", "The id we received is higher than the higher allowed value.");
      return -1;
    } catch (Exception e) {
      Log.e("GeckoSmsManager", "Something went wrong when trying to write a sent message", e);
      return -1;
    }
  }

  public void getMessage(int aMessageId, int aRequestId, long aProcessId) {
    class GetMessageRunnable implements Runnable {
      private int mMessageId;
      private int mRequestId;
      private long mProcessId;

      GetMessageRunnable(int aMessageId, int aRequestId, long aProcessId) {
        mMessageId = aMessageId;
        mRequestId = aRequestId;
        mProcessId = aProcessId;
      }

      @Override
      public void run() {
        class NotFoundException extends Exception { }
        class UnmatchingIdException extends Exception { }
        class TooManyResultsException extends Exception { }
        class InvalidTypeException extends Exception { }

        Cursor cursor = null;

        try {
          ContentResolver cr = GeckoApp.mAppContext.getContentResolver();
          Uri message = ContentUris.withAppendedId(kSmsContentUri, mMessageId);

          cursor = cr.query(message, kRequiredMessageRows, null, null, null);
          if (cursor == null || cursor.getCount() == 0) {
            throw new NotFoundException();
          }

          if (cursor.getCount() != 1) {
            throw new TooManyResultsException();
          }

          cursor.moveToFirst();

          if (cursor.getInt(cursor.getColumnIndex("_id")) != mMessageId) {
            throw new UnmatchingIdException();
          }

          int type = cursor.getInt(cursor.getColumnIndex("type"));
          String sender = "";
          String receiver = "";

          if (type == kSmsTypeInbox) {
            sender = cursor.getString(cursor.getColumnIndex("address"));
          } else if (type == kSmsTypeSentbox) {
            receiver = cursor.getString(cursor.getColumnIndex("address"));
          } else {
            throw new InvalidTypeException();
          }

          GeckoAppShell.notifyGetSms(cursor.getInt(cursor.getColumnIndex("_id")),
                                     receiver, sender,
                                     cursor.getString(cursor.getColumnIndex("body")),
                                     cursor.getLong(cursor.getColumnIndex("date")),
                                     mRequestId, mProcessId);
        } catch (NotFoundException e) {
          Log.i("GeckoSmsManager", "Message id " + mMessageId + " not found");
          GeckoAppShell.notifyGetSmsFailed(kNotFoundError, mRequestId, mProcessId);
        } catch (UnmatchingIdException e) {
          Log.e("GeckoSmsManager", "Requested message id (" + mMessageId +
                                   ") is different from the one we got.");
          GeckoAppShell.notifyGetSmsFailed(kUnknownError, mRequestId, mProcessId);
        } catch (TooManyResultsException e) {
          Log.e("GeckoSmsManager", "Get too many results for id " + mMessageId);
          GeckoAppShell.notifyGetSmsFailed(kUnknownError, mRequestId, mProcessId);
        } catch (InvalidTypeException e) {
          Log.i("GeckoSmsManager", "Message has an invalid type, we ignore it.");
          GeckoAppShell.notifyGetSmsFailed(kNotFoundError, mRequestId, mProcessId);
        } catch (Exception e) {
          Log.e("GeckoSmsManager", "Error while trying to get message", e);
          GeckoAppShell.notifyGetSmsFailed(kUnknownError, mRequestId, mProcessId);
        } finally {
          if (cursor != null) {
            cursor.close();
          }
        }
      }
    }

    if (!SmsIOThread.getInstance().execute(new GetMessageRunnable(aMessageId, aRequestId, aProcessId))) {
      Log.e("GeckoSmsManager", "Failed to add GetMessageRunnable to the SmsIOThread");
      GeckoAppShell.notifyGetSmsFailed(kUnknownError, aRequestId, aProcessId);
    }
  }

  public void deleteMessage(int aMessageId, int aRequestId, long aProcessId) {
    class DeleteMessageRunnable implements Runnable {
      private int mMessageId;
      private int mRequestId;
      private long mProcessId;

      DeleteMessageRunnable(int aMessageId, int aRequestId, long aProcessId) {
        mMessageId = aMessageId;
        mRequestId = aRequestId;
        mProcessId = aProcessId;
      }

      @Override
      public void run() {
        class TooManyResultsException extends Exception { }

        try {
          ContentResolver cr = GeckoApp.mAppContext.getContentResolver();
          Uri message = ContentUris.withAppendedId(kSmsContentUri, mMessageId);

          int count = cr.delete(message, null, null);

          if (count > 1) {
            throw new TooManyResultsException();
          }

          GeckoAppShell.notifySmsDeleted(count == 1, mRequestId, mProcessId);
        } catch (TooManyResultsException e) {
          Log.e("GeckoSmsManager", "Delete more than one message?", e);
          GeckoAppShell.notifySmsDeleteFailed(kUnknownError, mRequestId, mProcessId);
        } catch (Exception e) {
          Log.e("GeckoSmsManager", "Error while trying to delete a message", e);
          GeckoAppShell.notifySmsDeleteFailed(kUnknownError, mRequestId, mProcessId);
        }
      }
    }

    if (!SmsIOThread.getInstance().execute(new DeleteMessageRunnable(aMessageId, aRequestId, aProcessId))) {
      Log.e("GeckoSmsManager", "Failed to add GetMessageRunnable to the SmsIOThread");
      GeckoAppShell.notifySmsDeleteFailed(kUnknownError, aRequestId, aProcessId);
    }
  }

  public void createMessageList(long aStartDate, long aEndDate, String[] aNumbers, int aNumbersCount, int aDeliveryState, boolean aReverse, int aRequestId, long aProcessId) {
    class CreateMessageListRunnable implements Runnable {
      private long     mStartDate;
      private long     mEndDate;
      private String[] mNumbers;
      private int      mNumbersCount;
      private int      mDeliveryState;
      private boolean  mReverse;
      private int      mRequestId;
      private long     mProcessId;

      CreateMessageListRunnable(long aStartDate, long aEndDate, String[] aNumbers, int aNumbersCount, int aDeliveryState, boolean aReverse, int aRequestId, long aProcessId) {
        mStartDate = aStartDate;
        mEndDate = aEndDate;
        mNumbers = aNumbers;
        mNumbersCount = aNumbersCount;
        mDeliveryState = aDeliveryState;
        mReverse = aReverse;
        mRequestId = aRequestId;
        mProcessId = aProcessId;
      }

      @Override
      public void run() {
        class UnexpectedDeliveryStateException extends Exception { };
        class InvalidTypeException extends Exception { }

        Cursor cursor = null;
        boolean closeCursor = true;

        try {
          // TODO: should use the |selectionArgs| argument in |ContentResolver.query()|.
          ArrayList<String> restrictions = new ArrayList<String>();

          if (mStartDate != 0) {
            restrictions.add("date >= " + mStartDate);
          }

          if (mEndDate != 0) {
            restrictions.add("date <= " + mEndDate);
          }

          if (mNumbersCount > 0) {
            String numberRestriction = "address IN ('" + mNumbers[0] + "'";

            for (int i=1; i<mNumbersCount; ++i) {
              numberRestriction += ", '" + mNumbers[i] + "'";
            }
            numberRestriction += ")";

            restrictions.add(numberRestriction);
          }

          if (mDeliveryState == kDeliveryStateUnknown) {
            restrictions.add("type IN ('" + kSmsTypeSentbox + "', '" + kSmsTypeInbox + "')");
          } else if (mDeliveryState == kDeliveryStateSent) {
            restrictions.add("type = " + kSmsTypeSentbox);
          } else if (mDeliveryState == kDeliveryStateReceived) {
            restrictions.add("type = " + kSmsTypeInbox);
          } else {
            throw new UnexpectedDeliveryStateException();
          }

          String restrictionText = restrictions.size() > 0 ? restrictions.get(0) : "";

          for (int i=1; i<restrictions.size(); ++i) {
            restrictionText += " AND " + restrictions.get(i);
          }

          ContentResolver cr = GeckoApp.mAppContext.getContentResolver();
          cursor = cr.query(kSmsContentUri, kRequiredMessageRows, restrictionText, null,
                            mReverse ? "date DESC" : "date ASC");

          if (cursor.getCount() == 0) {
            GeckoAppShell.notifyNoMessageInList(mRequestId, mProcessId);
            return;
          }

          cursor.moveToFirst();

          int type = cursor.getInt(cursor.getColumnIndex("type"));
          String sender = "";
          String receiver = "";

          if (type == kSmsTypeInbox) {
            sender = cursor.getString(cursor.getColumnIndex("address"));
          } else if (type == kSmsTypeSentbox) {
            receiver = cursor.getString(cursor.getColumnIndex("address"));
          } else {
            throw new UnexpectedDeliveryStateException();
          }

          int listId = MessagesListManager.getInstance().add(cursor);
          closeCursor = false;
          GeckoAppShell.notifyListCreated(listId,
                                          cursor.getInt(cursor.getColumnIndex("_id")),
                                          receiver, sender,
                                          cursor.getString(cursor.getColumnIndex("body")),
                                          cursor.getLong(cursor.getColumnIndex("date")),
                                          mRequestId, mProcessId);
        } catch (UnexpectedDeliveryStateException e) {
          Log.e("GeckoSmsManager", "Unexcepted delivery state type", e);
          GeckoAppShell.notifyReadingMessageListFailed(kUnknownError, mRequestId, mProcessId);
        } catch (Exception e) {
          Log.e("GeckoSmsManager", "Error while trying to create a message list cursor", e);
          GeckoAppShell.notifyReadingMessageListFailed(kUnknownError, mRequestId, mProcessId);
        } finally {
          // Close the cursor if MessagesListManager isn't taking care of it.
          // We could also just check if it is in the MessagesListManager list but
          // that would be less efficient.
          if (cursor != null && closeCursor) {
            cursor.close();
          }
        }
      }
    }

    if (!SmsIOThread.getInstance().execute(new CreateMessageListRunnable(aStartDate, aEndDate, aNumbers, aNumbersCount, aDeliveryState, aReverse, aRequestId, aProcessId))) {
      Log.e("GeckoSmsManager", "Failed to add CreateMessageListRunnable to the SmsIOThread");
      GeckoAppShell.notifyReadingMessageListFailed(kUnknownError, aRequestId, aProcessId);
    }
  }

  public void getNextMessageInList(int aListId, int aRequestId, long aProcessId) {
    class GetNextMessageInListRunnable implements Runnable {
      private int mListId;
      private int mRequestId;
      private long mProcessId;

      GetNextMessageInListRunnable(int aListId, int aRequestId, long aProcessId) {
        mListId = aListId;
        mRequestId = aRequestId;
        mProcessId = aProcessId;
      }

      @Override
      public void run() {
        class UnexpectedDeliveryStateException extends Exception { };

        try {
          Cursor cursor = MessagesListManager.getInstance().get(mListId);

          if (!cursor.moveToNext()) {
            MessagesListManager.getInstance().remove(mListId);
            GeckoAppShell.notifyNoMessageInList(mRequestId, mProcessId);
            return;
          }

          int type = cursor.getInt(cursor.getColumnIndex("type"));
          String sender = "";
          String receiver = "";

          if (type == kSmsTypeInbox) {
            sender = cursor.getString(cursor.getColumnIndex("address"));
          } else if (type == kSmsTypeSentbox) {
            receiver = cursor.getString(cursor.getColumnIndex("address"));
          } else {
            throw new UnexpectedDeliveryStateException();
          }

          int listId = MessagesListManager.getInstance().add(cursor);
          GeckoAppShell.notifyGotNextMessage(cursor.getInt(cursor.getColumnIndex("_id")),
                                             receiver, sender,
                                             cursor.getString(cursor.getColumnIndex("body")),
                                             cursor.getLong(cursor.getColumnIndex("date")),
                                             mRequestId, mProcessId);
        } catch (UnexpectedDeliveryStateException e) {
          Log.e("GeckoSmsManager", "Unexcepted delivery state type", e);
          GeckoAppShell.notifyReadingMessageListFailed(kUnknownError, mRequestId, mProcessId);
        } catch (Exception e) {
          Log.e("GeckoSmsManager", "Error while trying to get the next message of a list", e);
          GeckoAppShell.notifyReadingMessageListFailed(kUnknownError, mRequestId, mProcessId);
        }
      }
    }

    if (!SmsIOThread.getInstance().execute(new GetNextMessageInListRunnable(aListId, aRequestId, aProcessId))) {
      Log.e("GeckoSmsManager", "Failed to add GetNextMessageInListRunnable to the SmsIOThread");
      GeckoAppShell.notifyReadingMessageListFailed(kUnknownError, aRequestId, aProcessId);
    }
  }

  public void clearMessageList(int aListId) {
    MessagesListManager.getInstance().remove(aListId);
  }

  public void stop() {
    GeckoApp.mAppContext.unregisterReceiver(this);
  }

  public void shutdown() {
    SmsIOThread.getInstance().interrupt();
    MessagesListManager.getInstance().clear();
  }
}
