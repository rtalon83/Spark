/**
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2006 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Lesser Public License (LGPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.spark.ui;

import org.jivesoftware.MainWindow;
import org.jivesoftware.Spark;
import org.jivesoftware.resource.SparkRes;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.filter.AndFilter;
import org.jivesoftware.smack.filter.FromContainsFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.spark.SparkManager;
import org.jivesoftware.spark.component.tabbedPane.SparkTab;
import org.jivesoftware.spark.component.tabbedPane.SparkTabbedPane;
import org.jivesoftware.spark.component.tabbedPane.SparkTabbedPaneListener;
import org.jivesoftware.spark.ui.rooms.ChatRoomImpl;
import org.jivesoftware.spark.ui.rooms.GroupChatRoom;
import org.jivesoftware.spark.ui.status.StatusItem;
import org.jivesoftware.spark.util.ModelUtil;
import org.jivesoftware.spark.util.SwingWorker;
import org.jivesoftware.spark.util.log.Log;
import org.jivesoftware.sparkimpl.settings.local.SettingsManager;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Should be subclassed to track all available ChatRooms.
 */
public class ChatContainer extends SparkTabbedPane implements MessageListener, ChangeListener {
    /**
     * List of all ChatRoom Listeners.
     */
    private final List chatRoomListeners = new ArrayList();
    private final List chatRoomList = new ArrayList();
    private final Map presenceMap = new HashMap();

    private static final String WELCOME_TITLE = SparkRes.getString(SparkRes.WELCOME);


    private ChatFrame chatFrame;

    /**
     * Creates the ChatRooms to hold all ChatRooms.
     */
    public ChatContainer() {
        // Set minimum size
        setMinimumSize(new Dimension(400, 200));
        // Don't allow tabs to shrink and allow scrolling.

        addSparkTabbedPaneListener(new SparkTabbedPaneListener() {
            public void tabRemoved(SparkTab tab, Component component, int index) {
                stateChanged(null);
                cleanupChatRoom((ChatRoom)component);
            }

            public void tabAdded(SparkTab tab, Component component, int index) {
                stateChanged(null);
            }

            public void tabSelected(SparkTab tab, Component component, int index) {
                stateChanged(null);
            }

            public void allTabsRemoved() {
                chatFrame.setTitle("");
                chatFrame.setVisible(false);
            }
        });

        setCloseButtonEnabled(true);

        // Add Key Navigation
        addKeyNavigation();

        this.setFocusable(false);
    }

    /**
     * Adds navigation capability to chat rooms. Users can navigate using the alt-left or right arrow keys.
     */
    private void addKeyNavigation() {
        KeyStroke leftStroke = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0);
        String leftStrokeString = org.jivesoftware.spark.util.StringUtils.keyStroke2String(leftStroke);

        // Handle Left Arrow
        this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt " + leftStrokeString + ""), "navigateLeft");
        this.getActionMap().put("navigateLeft", new AbstractAction("navigateLeft") {
            public void actionPerformed(ActionEvent evt) {
                int selectedIndex = getSelectedIndex();
                if (selectedIndex > 0) {
                    setSelectedIndex(selectedIndex - 1);
                }
                else {
                    setSelectedIndex(getTabCount() - 1);
                }
            }
        });

        KeyStroke rightStroke = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0);
        String rightStrokeString = org.jivesoftware.spark.util.StringUtils.keyStroke2String(rightStroke);

        // Handle Right Arrow
        this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt " + rightStrokeString + ""), "navigateRight");
        this.getActionMap().put("navigateRight", new AbstractAction("navigateRight") {
            public void actionPerformed(ActionEvent evt) {
                int selectedIndex = getSelectedIndex();
                if (selectedIndex > -1) {
                    int count = getTabCount();
                    if (selectedIndex == (count - 1)) {
                        setSelectedIndex(0);
                    }
                    else {
                        setSelectedIndex(selectedIndex + 1);
                    }
                }
            }
        });

        this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "escape");
        this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control W"), "escape");

        this.getActionMap().put("escape", new AbstractAction("escape") {
            public void actionPerformed(ActionEvent evt) {
                closeActiveRoom();
            }
        });

    }


    /**
     * Adds a new ChatRoom to Spark.
     *
     * @param room the ChatRoom to add.
     */
    public void addChatRoom(final ChatRoom room) {
        createFrameIfNeeded();

        room.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        AndFilter presenceFilter = new AndFilter(new PacketTypeFilter(Presence.class), new FromContainsFilter(room.getRoomname()));

        // Next, create a packet listener. We use an anonymous inner class for brevity.
        PacketListener myListener = new PacketListener() {
            public void processPacket(final Packet packet) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        handleRoomPresence((Presence)packet);
                    }
                });
            }
        };


        SparkManager.getConnection().addPacketListener(myListener, presenceFilter);

        // Add to PresenceMap
        presenceMap.put(room.getRoomname(), myListener);

        String tooltip = "";
        if (room instanceof ChatRoomImpl) {
            tooltip = ((ChatRoomImpl)room).getParticipantJID();
            String nickname = SparkManager.getUserManager().getUserNicknameFromJID(((ChatRoomImpl)room).getParticipantJID());

            tooltip = "<html><body><b>Contact:&nbsp;</b>" + nickname + "<br><b>JID:&nbsp;</b>" + tooltip;
        }
        else {
            tooltip = ((GroupChatRoom)room).getRoomname();
        }

        // Create ChatRoom UI and dock
        addTab(room.getTabTitle(), room.getTabIcon(), room, tooltip);


        room.addMessageListener(this);

        // Remove brand panel
        final String title = getTabAt(0).getActualText();
        if (title.equals(WELCOME_TITLE)) {
            chatFrame.setTitle(room.getRoomTitle());
        }

        SwingWorker worker = new SwingWorker() {
            public Object construct() {
                try {
                    Thread.sleep(100);
                }
                catch (InterruptedException e1) {
                    Log.error(e1);
                }
                return "";
            }

            public void finished() {
                checkVisibility(room);
            }
        };

        worker.start();

        // Add to ChatRoomList
        chatRoomList.add(room);

        fireChatRoomOpened(room);

        focusChat();
    }

    /**
     * Handles the presence of a one to one chat room.
     *
     * @param p the presence to handle.
     */
    private void handleRoomPresence(final Presence p) {
        final String roomname = StringUtils.parseBareAddress(p.getFrom());
        ChatRoom chatRoom;
        try {
            chatRoom = getChatRoom(roomname);
        }
        catch (ChatRoomNotFoundException e1) {
            Log.error("Could not locate chat room.", e1);
            return;
        }

        final String userid = StringUtils.parseResource(p.getFrom());
        if (p.getType() == Presence.Type.UNAVAILABLE) {
            fireUserHasLeft(chatRoom, userid);
        }
        else if (p.getType() == Presence.Type.AVAILABLE) {
            fireUserHasJoined(chatRoom, userid);
        }

        // Change tab icon
        if (chatRoom instanceof ChatRoomImpl) {
            StatusItem statusItem = SparkManager.getWorkspace().getStatusBar().getItemFromPresence(p);
            Roster roster = SparkManager.getConnection().getRoster();
            Icon tabIcon = null;
            if (statusItem == null && p == null) {
                tabIcon = SparkRes.getImageIcon(SparkRes.CLEAR_BALL_ICON);
            }
            else if (statusItem == null && p != null && p.getType() == Presence.Type.AVAILABLE) {
                tabIcon = SparkRes.getImageIcon(SparkRes.GREEN_BALL);
            }
            else {
                String status = p.getStatus();
                if (status != null && status.indexOf("phone") != -1) {
                    tabIcon = SparkRes.getImageIcon(SparkRes.ON_PHONE_IMAGE);
                }
                else if (statusItem == null) {
                    tabIcon = SparkRes.getImageIcon(SparkRes.CLEAR_BALL_ICON);
                }
                else {
                    tabIcon = statusItem.getIcon();
                }
            }

            int tabLoc = indexOfComponent(chatRoom);
            if (tabLoc != -1) {
                getTabAt(tabLoc).setIcon(tabIcon);
            }
        }
    }

    private void checkVisibility(ChatRoom chatRoom) {
        if (!chatFrame.isVisible() && SparkManager.getMainWindow().isFocused()) {
            chatFrame.setState(Frame.NORMAL);
            chatFrame.setVisible(true);
        }
        else if (chatFrame.isVisible() && !chatFrame.isInFocus()) {
            flashWindow(chatRoom);
        }
        else if (chatFrame.isVisible() && chatFrame.getState() == Frame.ICONIFIED) {
            // Set to new tab.
            int tabLocation = indexOfComponent(chatRoom);
            setSelectedIndex(tabLocation);

            // If the ContactList is in the tray, we need better notification by flashing
            // the chatframe.
            flashWindow(chatRoom);
        }

        // Handle when chat frame is visible but the Contact List is not.
        else if (chatFrame.isVisible() && !SparkManager.getMainWindow().isVisible()) {
            flashWindow(chatRoom);
        }
        else if (!chatFrame.isVisible()) {
            if (Spark.isWindows()) {
                chatFrame.setFocusableWindowState(false);
                chatFrame.setState(Frame.ICONIFIED);
            }
            chatFrame.setVisible(true);

            // Set to new tab.
            int tabLocation = indexOfComponent(chatRoom);
            setSelectedIndex(tabLocation);

            // If the ContactList is in the tray, we need better notification by flashing
            // the chatframe.
            if (!SparkManager.getMainWindow().isVisible()) {
                flashWindow(chatRoom);
            }
            else if (chatFrame.getState() == Frame.ICONIFIED) {
                flashWindow(chatRoom);
            }

        }
    }


    /**
     * Removes the ChatRoom resources.
     *
     * @param room the room to remove.
     */
    private void cleanupChatRoom(ChatRoom room) {
        fireChatRoomClosed(room);
        room.removeMessageListener(this);


        final PacketListener listener = (PacketListener)presenceMap.get(room.getRoomname());
        if (listener != null) {
            SparkManager.getConnection().removePacketListener(listener);
        }

        // Remove mappings
        presenceMap.remove(room.getRoomname());

        chatRoomList.remove(room);
    }

    /**
     * Close all chat rooms.
     */
    public void closeAllChatRooms() {
        Iterator iter = new ArrayList(chatRoomList).iterator();
        while (iter.hasNext()) {
            ChatRoom chatRoom = (ChatRoom)iter.next();
            closeTab(chatRoom);
            chatRoom.closeChatRoom();
        }
    }

    /**
     * Leaves a ChatRoom. Leaving a chat room does everything but close the room itself.
     *
     * @param room the room to leave.
     */
    public void leaveChatRoom(ChatRoom room) {
        // Notify that the chatroom has been left.
        fireChatRoomLeft(room);
        room.leaveChatRoom();

        // Setting the tab to be "disabled". Will not actually disable the tab because
        // that doesn't allow for selection.
        final int location = indexOfComponent(room);
        if (location != -1) {
//            setBackgroundAt(location, Color.GRAY);
            //          setForegroundAt(location, Color.GRAY);
            //        setIconAt(location, null);
        }

        final PacketListener listener = (PacketListener)presenceMap.get(room.getRoomname());
        if (listener != null) {
            SparkManager.getConnection().removePacketListener(listener);
        }
    }

    /**
     * Returns a ChatRoom by name.
     *
     * @param roomName the name of the ChatRoom.
     * @return the ChatRoom
     * @throws ChatRoomNotFoundException
     */
    public ChatRoom getChatRoom(String roomName) throws ChatRoomNotFoundException {
        for (int i = 0; i < getTabCount(); i++) {
            ChatRoom room = null;
            try {
                room = getChatRoom(i);
            }
            catch (ChatRoomNotFoundException e1) {
                // Ignore
            }

            if (room != null && room.getRoomname().equalsIgnoreCase(roomName) && room.isActive()) {
                return room;
            }
        }
        throw new ChatRoomNotFoundException(roomName + " not found.");
    }

    /**
     * Returns a ChatRoom in the specified tab location.
     *
     * @param location the tab location.
     * @return the ChatRoom found.
     * @throws ChatRoomNotFoundException
     */
    public ChatRoom getChatRoom(int location) throws ChatRoomNotFoundException {
        if (getTabCount() < location) {
            return null;
        }
        try {
            Component comp = getComponentAt(location);
            if (comp != null && comp instanceof ChatRoom) {
                return (ChatRoom)comp;
            }
        }
        catch (ArrayIndexOutOfBoundsException outOfBoundsEx) {
            Log.error("Error getting Chat Room", outOfBoundsEx);
        }

        throw new ChatRoomNotFoundException();
    }

    /**
     * Returns the Active ChatRoom.
     *
     * @return the ChatRoom active in the tabbed pane.
     * @throws ChatRoomNotFoundException is thrown if no chat room is found.
     */
    public ChatRoom getActiveChatRoom() throws ChatRoomNotFoundException {
        int location = getSelectedIndex();
        if (location != -1) {
            return getChatRoom(location);
        }
        throw new ChatRoomNotFoundException();
    }

    /**
     * Returns all the ChatRooms found in the UI.
     *
     * @return all ChatRooms found in the UI.
     */
    public Iterator getAllChatRooms() {
        return chatRoomList.iterator();
    }

    /**
     * Activates the specified ChatRoom.
     *
     * @param room the ChatRoom to activate.
     */
    public void activateChatRoom(ChatRoom room) {
        int tabLocation = indexOfComponent(room);
        setSelectedIndex(tabLocation);
        if (!chatFrame.isVisible()) {
            chatFrame.setVisible(true);
        }

        if (chatFrame.getState() == Frame.ICONIFIED) {
            chatFrame.setState(Frame.NORMAL);
        }
        chatFrame.requestFocus();
        focusChat();
    }

    /**
     * Activates the component in tabbed pane.
     *
     * @param component the component contained within the tab to activate.
     */
    public void activateComponent(Component component) {
        int tabLocation = indexOfComponent(component);
        if (tabLocation != -1) {
            setSelectedIndex(tabLocation);
        }
    }

    /**
     * Used for Tray Notifications.
     *
     * @param room    the ChatRoom where the message was received.
     * @param message the message received.
     */
    public void messageReceived(ChatRoom room, Message message) {
        // Check to see if it's a room update.
        String from = message.getFrom();
        String insertMessage = message.getBody();
        if (room.getChatType() == Message.Type.CHAT) {
            from = StringUtils.parseName(from);
        }
        else {
            from = StringUtils.parseResource(from);
        }

        if (ModelUtil.hasLength(from)) {
            insertMessage = from + ": " + insertMessage;
        }

        fireNotifyOnMessage(room);
    }

    public void fireNotifyOnMessage(final ChatRoom chatRoom) {
        ChatRoom activeChatRoom = null;
        try {
            activeChatRoom = getActiveChatRoom();
        }
        catch (ChatRoomNotFoundException e1) {
            Log.error(e1);
        }

        if (chatFrame.isVisible() && (chatFrame.getState() == Frame.ICONIFIED || chatFrame.getInactiveTime() > 60000)) {
            int tabLocation = indexOfComponent(chatRoom);
            setSelectedIndex(tabLocation);
            startFlashing(chatRoom);
            return;
        }

        if (!chatFrame.isVisible() && SparkManager.getMainWindow().isFocused()) {
            chatFrame.setState(Frame.NORMAL);
            chatFrame.setVisible(true);
        }
        else if (chatFrame.isVisible() && !chatFrame.isInFocus()) {
            startFlashing(chatRoom);
        }
        else if (chatFrame.isVisible() && chatFrame.getState() == Frame.ICONIFIED) {
            // Set to new tab.
            int tabLocation = indexOfComponent(chatRoom);
            setSelectedIndex(tabLocation);

            // If the ContactList is in the tray, we need better notification by flashing
            // the chatframe.
            startFlashing(chatRoom);
        }

        // Handle when chat frame is visible but the Contact List is not.
        else if (chatFrame.isVisible() && !SparkManager.getMainWindow().isVisible() && !chatFrame.isInFocus()) {
            startFlashing(chatRoom);
        }
        else if (!chatFrame.isVisible()) {
            if (Spark.isWindows()) {
                chatFrame.setFocusableWindowState(false);
                chatFrame.setState(Frame.ICONIFIED);
            }
            chatFrame.setVisible(true);

            // Set to new tab.
            int tabLocation = indexOfComponent(chatRoom);
            setSelectedIndex(tabLocation);

            // If the ContactList is in the tray, we need better notification by flashing
            // the chatframe.
            if (!SparkManager.getMainWindow().isVisible()) {
                startFlashing(chatRoom);
            }
            else if (chatFrame.getState() == Frame.ICONIFIED) {
                startFlashing(chatRoom);
            }

        }
        else if (chatRoom != activeChatRoom) {
            startFlashing(chatRoom);
        }

    }

    public void messageSent(ChatRoom room, Message message) {
    }

    /**
     * Notification that the tab pane has been modified. Generally by changing of the tabs.
     *
     * @param e the ChangeEvent.
     */
    public void stateChanged(ChangeEvent e) {
        stopFlashing();

        // Request focus in Chat Area if selected
        final ChatRoom room;
        try {
            room = getActiveChatRoom();

            focusChat();

            // Set the title of the room.
            chatFrame.setTitle(room.getRoomTitle());
        }
        catch (ChatRoomNotFoundException e1) {
            // Ignore
        }

    }


    private void stopFlashing() {
        // Get current tab
        int sel = getSelectedIndex();
        if (sel != -1) {
            final ChatRoom room;
            try {
                room = getChatRoom(sel);
                stopFlashing(room);
            }
            catch (ChatRoomNotFoundException e1) {
                //AgentLog.logError("Could not find chat room.", e1);
            }
        }
    }


    /**
     * Closes a tab of a room.
     *
     * @param component the component inside of the tab to close.
     */
    public void closeTab(Component component) {
        int location = indexOfComponent(component);
        if (location == -1) {
            return;
        }

        if (getTabCount() == 0) {
            chatFrame.setTitle("");
            chatFrame.setVisible(false);
        }

        this.removeTabAt(location);
    }

    public void closeActiveRoom() {
        ChatRoom room = null;
        try {
            room = getActiveChatRoom();
        }
        catch (ChatRoomNotFoundException e1) {
            // AgentLog.logError("Chat room not found", e1);
        }

        // Confirm end session
        boolean isGroupChat = room.getChatType() == Message.Type.GROUP_CHAT;
        String message = "Would you like to end this session?";
        if (true) {
            room.closeChatRoom();
            return;
        }
        else {
            if (!room.isActive()) {
                room.closeChatRoom();
                return;
            }
        }

        final int ok = JOptionPane.showConfirmDialog(SparkManager.getMainWindow(), message,
                "Confirmation", JOptionPane.YES_NO_OPTION);
        if (ok == JOptionPane.OK_OPTION) {
            room.closeChatRoom();
            return;
        }
    }

    public String toString() {
        StringBuffer buf = new StringBuffer();
        Iterator iter = chatRoomList.iterator();
        while (iter.hasNext()) {
            ChatRoom room = (ChatRoom)iter.next();
            buf.append("Roomname=").append(room.getRoomname()).append("\n");
        }
        return buf.toString();
    }


    /**
     * Returns true if there are any Rooms present.
     *
     * @return true if Rooms are present, otherwise false.
     */
    public boolean hasRooms() {
        int count = getSelectedIndex();
        return count != -1;
    }

    /**
     * Adds a ChatRoom listener to ChatRooms. The
     * listener will be called when either a ChatRoom has been
     * added, removed, or activated.
     *
     * @param listener the <code>ChatRoomListener</code> to register
     */
    public void addChatRoomListener(ChatRoomListener listener) {
        if (!chatRoomListeners.contains(listener)) {
            chatRoomListeners.add(listener);
        }
    }

    /**
     * Removes the specified <code>ChatRoomListener</code>.
     *
     * @param listener the <code>ChatRoomListener</code> to remove
     */
    public void removeChatRoomListener(ChatRoomListener listener) {
        chatRoomListeners.remove(listener);
    }

    /**
     * Notifies users that a <code>ChatRoom</code> has been opened.
     *
     * @param room - the <code>ChatRoom</code> that has been opened.
     */
    protected void fireChatRoomOpened(ChatRoom room) {
        final Iterator iter = new ArrayList(chatRoomListeners).iterator();
        while (iter.hasNext()) {
            ((ChatRoomListener)iter.next()).chatRoomOpened(room);
        }
    }

    /**
     * Notifies users that a <code>ChatRoom</code> has been left.
     *
     * @param room - the <code>ChatRoom</code> that has been left
     */
    protected void fireChatRoomLeft(ChatRoom room) {
        final Iterator iter = new HashSet(chatRoomListeners).iterator();
        while (iter.hasNext()) {
            final Object chatRoomListener = iter.next();
            ((ChatRoomListener)chatRoomListener).chatRoomLeft(room);
        }
    }

    /**
     * Notifies users that a <code>ChatRoom</code> has been closed.
     *
     * @param room - the <code>ChatRoom</code> that has been closed.
     */
    protected void fireChatRoomClosed(ChatRoom room) {
        final Iterator iter = new HashSet(chatRoomListeners).iterator();
        while (iter.hasNext()) {
            final Object chatRoomListener = iter.next();
            ((ChatRoomListener)chatRoomListener).chatRoomClosed(room);
        }
    }

    /**
     * Notifies users that a <code>ChatRoom</code> has been activated.
     *
     * @param room - the <code>ChatRoom</code> that has been activated.
     */
    protected void fireChatRoomActivated(ChatRoom room) {
        final Iterator iter = new HashSet(chatRoomListeners).iterator();
        while (iter.hasNext()) {
            ((ChatRoomListener)iter.next()).chatRoomActivated(room);
        }
    }

    /**
     * Notifies users that a user has joined a <code>ChatRoom</code>.
     *
     * @param room   - the <code>ChatRoom</code> that a user has joined.
     * @param userid - the userid of the person.
     */
    protected void fireUserHasJoined(final ChatRoom room, final String userid) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                final Iterator iter = new HashSet(chatRoomListeners).iterator();
                while (iter.hasNext()) {
                    ((ChatRoomListener)iter.next()).userHasJoined(room, userid);
                }
            }
        });

    }

    /**
     * Notifies users that a user has left a <code>ChatRoom</code>.
     *
     * @param room   - the <code>ChatRoom</code> that a user has left.
     * @param userid - the userid of the person.
     */
    protected void fireUserHasLeft(final ChatRoom room, final String userid) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                final Iterator iter = new HashSet(chatRoomListeners).iterator();
                while (iter.hasNext()) {
                    ((ChatRoomListener)iter.next()).userHasLeft(room, userid);
                }
            }
        });

    }

    /**
     * Starts flashing of MainWindow.
     *
     * @param room the ChatRoom to check if a message has been inserted
     *             but the room is not the selected room.
     */
    public void startFlashing(final ChatRoom room) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    final int index = indexOfComponent(room);
                    if (index != -1) {
                        room.increaseUnreadMessageCount();
                        int unreadMessageCount = room.getUnreadMessageCount();
                        String appendedMessage = "";
                        if (unreadMessageCount > 1) {
                            appendedMessage = " (" + unreadMessageCount + ")";
                        }

                        SparkTab tab = getTabAt(index);
                        tab.getTitleLabel().setText(room.getTabTitle() + appendedMessage);

                        makeTabRed(room);
                    }

                    boolean invokeFlash = !SettingsManager.getLocalPreferences().isChatRoomNotificationsOff() || !(room instanceof GroupChatRoom);

                    if (!chatFrame.isFocused() && invokeFlash) {
                        SparkManager.getAlertManager().flashWindow(chatFrame);
                    }
                }
                catch (Exception ex) {
                    Log.error("Issue in ChatRooms with tab location.", ex);
                }
            }
        });
    }

    public void flashWindow(final ChatRoom room) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    boolean invokeFlash = !SettingsManager.getLocalPreferences().isChatRoomNotificationsOff() || !(room instanceof GroupChatRoom);

                    if (!chatFrame.isFocused() && invokeFlash) {
                        SparkManager.getAlertManager().flashWindow(chatFrame);
                    }
                }
                catch (Exception ex) {
                    Log.error("Issue in ChatRooms with tab location.", ex);
                }
            }
        });
    }

    public void makeTabRed(final ChatRoom room) {
        final int index = indexOfComponent(room);
        if (index != -1) {
            SparkTab tab = getTabAt(index);
            Font font = tab.getTitleLabel().getFont();

            final JLabel titleLabel = tab.getTitleLabel();
            Font newFont = font.deriveFont(Font.BOLD);
            titleLabel.setFont(newFont);
            titleLabel.setForeground(Color.red);
        }
    }

    public void useTabDefault(final ChatRoom room) {
        final int index = indexOfComponent(room);
        if (index != -1) {
            SparkTab tab = getTabAt(index);
            Font font = tab.getTitleLabel().getFont();
            tab.getTitleLabel().setForeground(Color.black);

            Font newFont = font.deriveFont(Font.PLAIN);
            tab.getTitleLabel().setFont(newFont);
        }
    }

    /**
     * Checks to see if the MainWindow should stop flashing.
     *
     * @param room the ChatRoom to check.
     */
    public void stopFlashing(final ChatRoom room) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    int index = indexOfComponent(room);
                    if (index != -1) {
                        SparkTab tab = getTabAt(index);
                        useTabDefault(room);
                        tab.getTitleLabel().setText(room.getTabTitle());
                        room.clearUnreadMessageCount();
                    }
                }
                catch (Exception ex) {
                    Log.error("Could not stop flashing for " + room + " because " + ex.getMessage(), ex);
                }

                SparkManager.getAlertManager().stopFlashing(chatFrame);
            }
        });
    }

    public void setChatRoomTitle(ChatRoom room, String title) {
        int index = indexOfComponent(room);
        if (index != -1) {
            SparkTab tab = getTabAt(index);
            useTabDefault(room);
            tab.getTitleLabel().setText(room.getTabTitle());
        }
    }

    private void createFrameIfNeeded() {
        if (chatFrame != null) {
            return;
        }
        chatFrame = new ChatFrame();


        chatFrame.addWindowListener(new WindowAdapter() {
            public void windowActivated(WindowEvent windowEvent) {
                stopFlashing();
                int sel = getSelectedIndex();
                if (sel == -1) {
                    return;
                }
                final ChatRoom room;
                try {
                    room = getChatRoom(sel);
                    focusChat();

                    // Set the title of the room.
                    chatFrame.setTitle(room.getRoomTitle());
                }
                catch (ChatRoomNotFoundException e1) {
                }

            }

            public void windowDeactivated(WindowEvent windowEvent) {
            }

        });
    }


    public void focusChat() {
        SwingWorker worker = new SwingWorker() {
            public Object construct() {
                try {
                    Thread.sleep(50);
                }
                catch (InterruptedException e1) {
                    Log.error(e1);
                }
                return "ok";
            }

            public void finished() {
                try {
                    //chatFrame.requestFocus();
                    ChatRoom chatRoom = getActiveChatRoom();
                    chatRoom.requestFocusInWindow();
                    chatRoom.getChatInputEditor().requestFocusInWindow();
                }
                catch (ChatRoomNotFoundException e1) {
                    // Ignore. There may legitamtly not be a chat room.
                }
            }
        };
        worker.start();

    }

    public Collection getChatRooms() {
        return chatRoomList;
    }

    public ChatFrame getChatFrame() {
        return chatFrame;
    }

    public void blinkFrameIfNecessary(final JFrame frame) {

        final MainWindow mainWindow = SparkManager.getMainWindow();

        if (mainWindow.isFocused()) {
            frame.setVisible(true);
            return;
        }
        else {
            // Set to new tab.
            if (Spark.isWindows()) {
                frame.setState(Frame.ICONIFIED);

                SparkManager.getAlertManager().flashWindow(frame);

                frame.setVisible(true);
                frame.addWindowListener(new WindowAdapter() {
                    public void windowActivated(WindowEvent e) {
                        SparkManager.getAlertManager().stopFlashing(frame);
                    }
                });
            }
        }
    }


}
