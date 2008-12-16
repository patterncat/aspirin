/*
 * Created on Jan 5, 2004
 * 
 * Copyright (c) 2004 Katherine Rhodes (masukomi at masukomi dot org)
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.masukomi.aspirin.core;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import javax.mail.MessagingException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.mailet.Mail;
import org.apache.mailet.MailAddress;
/**
 * A QuedItem contains a Mail object, a list of MailWatchers, and assorted
 * variables to manage it's place in the que and retries.
 * 
 * @author kate rhodes masukomi at masukomi dot org
 */
public class QuedItem implements Comparable {
	static private Log log = LogFactory.getLog(QuedItem.class);
	/** A Collection of MailWatchers */
	//protected Collection watchers;
	/** The mail to be sent */
	protected Mail mail;

	/** DOCUMENT ME! */
	protected long nextAttempt;
	static public int IN_QUE = 0;
	static public int IN_PROCESS = 1;
	static public int COMPLETED = 3;
	protected int status = 0;
	protected HashMap<MailAddress, Integer> recipientFailures;
	protected HashMap<MailAddress, Integer> recipientSuccesses;
	protected int numSuccesses;
	protected int numFailures;
	/**
	 *  
	 */
	public QuedItem(Mail mail) {
		this.mail = mail;
		//this.watchers = listeners;
		nextAttempt = System.currentTimeMillis();
	}
	/**
	 * 
	 * 
	 * @return the Mail message
	 */
	public Mail getMail() {
		return mail;
	}
	/**
	 * 
	 * 
	 * @return The time in milliseconds when the system will next be able to attempt a resend of this mail
	 */
	public long getNextAttempt() {
		return nextAttempt;
	}
	/**
	 * @deprecated
	 * 
	 * @return zero This method is no longer applicable
	 */
	public int getNumAttempts() {
		return 0;
	}
	/**
	 * Used to sort items by their next attempt time
	 * 
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	public int compareTo(Object o) {
		try {
			if (((QuedItem) o).getNextAttempt() > getNextAttempt()) {
				return -1;
			} else if (((QuedItem) o).getNextAttempt() < getNextAttempt()) {
				return 1; // that one should go first
			}
			return 0;
		} catch (ClassCastException cce) {
			return 0;
		}
	}
	/**
	 * Sends a failure notice to any watchers about the current mail and recipient.
	 */
	public void failForRecipient(MailQue que, MailAddress recipient) {
		numFailures++;
		if (recipientFailures == null) {
			recipientFailures = new HashMap<MailAddress, Integer>();
		}
		recipientFailures.put(recipient, 
				new Integer(Configuration.getInstance().getMaxAttempts())); 
		// tell anyone who cares
		if (que.getListeners() != null) {
			que.incrementNotifiersCount();
			for (MailWatcher watcher : que.getListeners()){
				try {
					watcher.deliveryFailure(que, getMail().getMessage(), recipient);
				} catch (MessagingException e) {
					log.error(e);
				}
			}
			que.decrementNotifiersCount(); 
			//TODO:  is that right? I mean, just because it failed for 
			// one recipient it doesn't mean the message is done sending
			
		}
		if (isCompleted()) {
			setStatus(COMPLETED);
			//this will flag it for removal from the que
		}
	}
	/**
	 * DOCUMENT ME!
	 */
	public void retry(MailQue que, MailAddress recipient) {
		que.reQue(this); // just in case
		if (retryable(recipient)) {
			// increment it's number of failures
			if (recipientFailures.containsKey(recipient)) {
				Integer numFailures = (Integer) recipientFailures.get(recipient);
				recipientFailures.put(recipient, new Integer(numFailures.intValue() +1));
			} else {
				recipientFailures.put(recipient, new Integer(1));
			}
			
			
			
			
			nextAttempt = System.currentTimeMillis()
					+ Configuration.getInstance().getRetryInterval();
			setStatus(QuedItem.IN_QUE);
			if (log.isTraceEnabled()) {
				log.trace("will retry message at "
						+ new Date(nextAttempt).toString());
			}
		} else {
			try {
				failForRecipient(que, recipient);
				Bouncer.bounce(que, getMail(), "Maxumum retries exceeded for " +recipient,
						Configuration.getInstance().getPostmaster());
			} catch (MessagingException e) {
				log.error(e);
			}
		}
	}
	/**
	 * @param i
	 */
	public void setStatus(int i) {
		status = i;
	}
	/**
	 * @return true if the current recipient can be retried again
	 */
	public boolean retryable(MailAddress recipient) {
		if (recipientFailures == null) {
			recipientFailures = new HashMap();
		}
		if (recipientFailures.containsKey(recipient)) {
			Integer numFailures = (Integer) recipientFailures.get(recipient);
			if ((numFailures.intValue() + 1) < Configuration.getInstance()
					.getMaxAttempts()) {
				return true;
			}
		} else {
			return true;
		}
		return false;
	}

	/**
	 * @return Returns the status.
	 */
	public int getStatus() {
		return status;
	}
	/**
	 * @return true if this message is ready to be retried
	 */
	public boolean isReadyToSend() {
		if (getStatus() != QuedItem.IN_QUE) {
			return false;
		}
		if (nextAttempt > System.currentTimeMillis()) {
			return false; // let's let this one cook a bit longer.
		}
		// could test if retryable in here but theoretically it can't not be
		return true;
	}
	/*
	 * public Collection getWatchers(){ return watchers; }
	 */
	/**
	 * called by RemoteDelivery when it successfully sends a message to a
	 * particular recipient
	 */
	public void succeededForRecipient(MailQue que, MailAddress recipient) {
		numSuccesses++;
		if (recipientSuccesses == null) {
			recipientSuccesses = new HashMap();
		}
		recipientSuccesses.put(recipient, null);
		
		if (que.getListeners() != null
				&& que.getListeners().size() > 0) {
			que.incrementNotifiersCount();
			Iterator it = que.getListeners().iterator();
			while (it.hasNext()) {
				MailWatcher watcher = (MailWatcher) it.next();
				try {
					//watcher.deliverySuccess(getMail().getMessage(), recipients);
					watcher.deliverySuccess(que, getMail().getMessage(), recipient);
				} catch (MessagingException e) {
					log.error(e);
				}
			}
			que.decrementNotifiersCount();
		}
		if (isCompleted()) {
			setStatus(COMPLETED);
			// this will flag it for removal from the que
		}
	}
	boolean isCompleted() {
		if (numSuccesses + numFailures >= getMail().getRecipients().size()) {
			return true;
		}
		return false;
	}
	
	boolean recepientHasBeenHandled(MailAddress recipient) {
		if (recipientSuccesses != null && recipientSuccesses.containsKey(recipient)) {
			return true;
		}
		
		if (recipientFailures != null && ((Integer)recipientFailures.get(recipient)).intValue() >2) {
			return true;
		}
		return false;
		
	}
}