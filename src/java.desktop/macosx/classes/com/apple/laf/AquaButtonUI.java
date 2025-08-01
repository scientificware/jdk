/*
 * Copyright (c) 2011, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.apple.laf;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseMotionListener;
import java.beans.PropertyChangeEvent;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.ButtonUI;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.RootPaneUI;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicButtonListener;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

import apple.laf.JRSUIConstants.Size;
import com.apple.laf.AquaButtonExtendedTypes.TypeSpecifier;
import com.apple.laf.AquaUtilControlSize.Sizeable;
import com.apple.laf.AquaUtils.RecyclableSingleton;
import com.apple.laf.AquaUtils.RecyclableSingletonFromDefaultConstructor;
import sun.swing.MnemonicHandler;
import sun.swing.SwingUtilities2;

public class AquaButtonUI extends BasicButtonUI implements Sizeable {
    private static final String BUTTON_TYPE = "JButton.buttonType";
    private static final String SEGMENTED_BUTTON_POSITION = "JButton.segmentPosition";

    private static final RecyclableSingleton<AquaButtonUI> buttonUI = new RecyclableSingletonFromDefaultConstructor<AquaButtonUI>(AquaButtonUI.class);
    public static ComponentUI createUI(final JComponent c) {
        return buttonUI.get();
    }

    // Has the shared instance defaults been initialized?
    private boolean defaults_initialized = false;
    private Color defaultDisabledTextColor = null;

    @Override
    protected void installDefaults(final AbstractButton b) {
        // load shared instance defaults
        final String pp = getPropertyPrefix();

        if (!defaults_initialized) {
            defaultDisabledTextColor = UIManager.getColor(pp + "disabledText");
            defaults_initialized = true;
        }

        setButtonMarginIfNeeded(b, UIManager.getInsets(pp + "margin"));

        LookAndFeel.installColorsAndFont(b, pp + "background", pp + "foreground", pp + "font");
        LookAndFeel.installProperty(b, "opaque", UIManager.getBoolean(pp + "opaque"));

        final Object borderProp = b.getClientProperty(BUTTON_TYPE);
        boolean hasBorder = false;

        if (borderProp != null) {
            hasBorder = setButtonType(b, borderProp);
        }
        if (!hasBorder) setThemeBorder(b);

        final Object segmentProp = b.getClientProperty(SEGMENTED_BUTTON_POSITION);
        if (segmentProp != null) {
            final Border border = b.getBorder();
            if (!(border instanceof AquaBorder)) return;

            b.setBorder(AquaButtonExtendedTypes.getBorderForPosition(b, b.getClientProperty(BUTTON_TYPE), segmentProp));
        }
    }

    @Override
    public void applySizeFor(final JComponent c, final Size size) {
        // this space intentionally left blank
        // (subclasses need to do work here)
     }

    protected void setThemeBorder(final AbstractButton b) {
        // Set the correct border
        final ButtonUI genericUI = b.getUI();
        if (!(genericUI instanceof AquaButtonUI)) return;
        final AquaButtonUI ui = (AquaButtonUI)genericUI;

        Border border = b.getBorder();
        if (!ui.isBorderFromProperty(b) && (border == null || border instanceof UIResource || border instanceof AquaButtonBorder)) {
            // See BasicGraphicsUtils.getPreferredButtonSize - it returns null for preferred size,
            // causing it to use the subcomponent's size, which doesn't allow space for Aqua pushbuttons
            boolean iconFont = true;
            if (isOnToolbar(b)) {
                if (b instanceof JToggleButton) {
                    border = AquaButtonBorder.getToolBarButtonBorder();
                } else {
                    border = AquaButtonBorder.getBevelButtonBorder();
                }
            } else if (b.getIcon() != null || b.getComponentCount() > 0) {
                // radar 3308129 && (b.getText() == null || b.getText().equals("")))
                // we used to only do this for buttons that had images and no text
                // now we do it for all buttons that have any images - they cannot
                // be a default button.
                border = AquaButtonBorder.getToggleButtonBorder();
            } else {
                border = UIManager.getBorder(getPropertyPrefix() + "border");
                iconFont = false;
            }

            b.setBorder(border);

            final Font currentFont = b.getFont();
            if (iconFont && (currentFont == null || currentFont instanceof UIResource)) {
                b.setFont(UIManager.getFont("IconButton.font"));
            }
        }
    }

    protected static boolean isOnToolbar(final AbstractButton b) {
        Component parent = b.getParent();
        while (parent != null) {
            if (parent instanceof JToolBar) return true;
            parent = parent.getParent();
        }
        return false;
    }

    // A state that affects border has changed.  Make sure we have the right one
    protected static void updateBorder(final AbstractButton b) {
        // See if the button has overridden the automatic button type
        final Object prop = b.getClientProperty(BUTTON_TYPE);
        if (prop != null) return;

        final ButtonUI ui = b.getUI();
        if (!(ui instanceof AquaButtonUI)) return;
        if (b.getBorder() != null) ((AquaButtonUI)ui).setThemeBorder(b);
    }

    protected void setButtonMarginIfNeeded(final AbstractButton b, final Insets insets) {
        final Insets margin = b.getMargin();
        if (margin == null || (margin instanceof UIResource)) {
            b.setMargin(insets);
        }
    }

    public boolean isBorderFromProperty(final AbstractButton button) {
        return button.getClientProperty(BUTTON_TYPE) != null;
    }

    protected boolean setButtonType(final AbstractButton b, final Object prop) {
        if (!(prop instanceof String)) {
            b.putClientProperty(BUTTON_TYPE, null); // so we know to use the automatic button type
            return false;
        }

        final String buttonType = (String)prop;
        boolean iconFont = true;

        final TypeSpecifier specifier = AquaButtonExtendedTypes.getSpecifierByName(buttonType);
        if (specifier != null) {
            b.setBorder(specifier.getBorder());
            iconFont = specifier.setIconFont;
        }

        final Font currentFont = b.getFont();
        if (currentFont == null || currentFont instanceof UIResource) {
            b.setFont(UIManager.getFont(iconFont ? "IconButton.font" : "Button.font"));
        }

        return true;
    }

    @Override
    protected void installListeners(final AbstractButton b) {
        super.installListeners(b);
        AquaButtonListener listener = getAquaButtonListener(b);
        if (listener != null) {
            // put the listener in the button's client properties so that
            // we can get at it later
            b.putClientProperty(this, listener);
        }
        installHierListener(b);
        AquaUtilControlSize.addSizePropertyListener(b);
    }

    @Override
    protected void installKeyboardActions(final AbstractButton b) {
        final BasicButtonListener listener = (BasicButtonListener)b.getClientProperty(this);
        if (listener != null) listener.installKeyboardActions(b);
    }

    // Uninstall PLAF
    @Override
    public void uninstallUI(final JComponent c) {
        uninstallKeyboardActions((AbstractButton)c);
        uninstallListeners((AbstractButton)c);
        uninstallDefaults((AbstractButton)c);
        //BasicHTML.updateRenderer(c, "");
    }

    @Override
    protected void uninstallKeyboardActions(final AbstractButton b) {
        final BasicButtonListener listener = (BasicButtonListener)b.getClientProperty(this);
        if (listener != null) listener.uninstallKeyboardActions(b);
    }

    @Override
    protected void uninstallListeners(final AbstractButton b) {
        super.uninstallListeners(b);
        b.putClientProperty(this, null);
        uninstallHierListener(b);
        AquaUtilControlSize.removeSizePropertyListener(b);
    }

    @Override
    protected void uninstallDefaults(final AbstractButton b) {
        LookAndFeel.uninstallBorder(b);
        defaults_initialized = false;
    }

    // Create Listeners
    @Override
    protected AquaButtonListener createButtonListener(final AbstractButton b) {
        return new AquaButtonListener(b);
    }

    /**
     * Returns the AquaButtonListener for the passed in Button, or null if one
     * could not be found.
     */
    private AquaButtonListener getAquaButtonListener(AbstractButton b) {
        MouseMotionListener[] listeners = b.getMouseMotionListeners();

        if (listeners != null) {
            for (MouseMotionListener listener : listeners) {
                if (listener instanceof AquaButtonListener) {
                    return (AquaButtonListener) listener;
                }
            }
        }
        return null;
    }

    // Paint Methods
    @Override
    public void paint(final Graphics g, final JComponent c) {
        final AbstractButton b = (AbstractButton)c;
        final ButtonModel model = b.getModel();

        final Insets i = c.getInsets();

        Rectangle viewRect = new Rectangle(b.getWidth(), b.getHeight());
        Rectangle iconRect = new Rectangle();
        Rectangle textRect = new Rectangle();

        // we are overdrawing here with translucent colors so we get
        // a darkening effect. How can we avoid it. Try clear rect?
        if (b.isOpaque()) {
            g.setColor(c.getBackground());
            g.fillRect(viewRect.x, viewRect.y, viewRect.width, viewRect.height);
        }

        AquaButtonBorder aquaBorder = null;
        if (((AbstractButton)c).isBorderPainted()) {
            final Border border = c.getBorder();

            if (border instanceof AquaButtonBorder) {
                // only do this if borders are on!
                // this also takes care of focus painting.
                aquaBorder = (AquaButtonBorder)border;
                aquaBorder.paintButton(c, g, viewRect.x, viewRect.y, viewRect.width, viewRect.height);
            }
        } else {
            if (b.isOpaque()) {
                viewRect.x = i.left - 2;
                viewRect.y = i.top - 2;
                viewRect.width = b.getWidth() - (i.right + viewRect.x) + 4;
                viewRect.height = b.getHeight() - (i.bottom + viewRect.y) + 4;
                if (b.isContentAreaFilled() || model.isSelected()) {
                    if (model.isSelected()) // Toggle buttons
                    g.setColor(c.getBackground().darker());
                    else g.setColor(c.getBackground());
                    g.fillRect(viewRect.x, viewRect.y, viewRect.width, viewRect.height);
                }
            }

            // needs focus to be painted
            // for now we don't know exactly what to do...we'll see!
            if (b.isFocusPainted() && b.hasFocus()) {
                // paint UI specific focus
                paintFocus(g, b, viewRect, textRect, iconRect);
            }
        }

        // performs icon and text rect calculations
        final String text = layoutAndGetText(g, b, aquaBorder, i, viewRect, iconRect, textRect);

        // Paint the Icon
        if (b.getIcon() != null) {
            paintIcon(g, b, iconRect);
        }

        if (textRect.width == 0) {
            textRect.width = 50;
        }

        if (text != null && !text.isEmpty()) {
            final View v = (View)c.getClientProperty(BasicHTML.propertyKey);
            if (v != null) {
                v.paint(g, textRect);
            } else {
                paintText(g, b, textRect, text);
            }
        }
    }

    @Override
    protected void paintFocus(Graphics g, AbstractButton b,
                              Rectangle viewRect, Rectangle textRect, Rectangle iconRect) {
        Graphics2D g2d = null;
        Stroke oldStroke = null;
        Object oldAntialiasingHint = null;
        Color oldColor = g.getColor();
        if (g instanceof Graphics2D) {
            g2d = (Graphics2D)g;
            oldStroke = g2d.getStroke();
            oldAntialiasingHint = g2d.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            g2d.setStroke(new BasicStroke(3));
            g2d.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

        }
        Color ringColor = UIManager.getColor("Focus.color");
        g.setColor(ringColor);
        g.drawRoundRect(5, 3, b.getWidth() - 10, b.getHeight() - 7, 15, 15);
        if (g2d != null) {
            // Restore old state of Java2D renderer
            g2d.setStroke(oldStroke);
            g2d.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    oldAntialiasingHint);
        }
        g.setColor(oldColor);
    }

    protected String layoutAndGetText(final Graphics g, final AbstractButton b, final AquaButtonBorder aquaBorder, final Insets i, Rectangle viewRect, Rectangle iconRect, Rectangle textRect) {
        // re-initialize the view rect to the selected insets
        viewRect.x = i.left;
        viewRect.y = i.top;
        viewRect.width = b.getWidth() - (i.right + viewRect.x);
        viewRect.height = b.getHeight() - (i.bottom + viewRect.y);

        // reset the text and icon rects
        textRect.x = textRect.y = textRect.width = textRect.height = 0;
        iconRect.x = iconRect.y = iconRect.width = iconRect.height = 0;

        // setup the font
        g.setFont(b.getFont());
        final FontMetrics fm = g.getFontMetrics();

        // layout the text and icon
        final String originalText = b.getText();
        final String text = SwingUtilities.layoutCompoundLabel(b, fm, originalText, b.getIcon(), b.getVerticalAlignment(), b.getHorizontalAlignment(), b.getVerticalTextPosition(), b.getHorizontalTextPosition(), viewRect, iconRect, textRect, originalText == null ? 0 : b.getIconTextGap());
        if (text == originalText || aquaBorder == null) return text; // everything fits

        // if the text didn't fit - check if the aqua border has alternate Insets that are more adhering
        final Insets alternateContentInsets = aquaBorder.getContentInsets(b, b.getWidth(), b.getHeight());
        if (alternateContentInsets != null) {
            // recursively call and don't pass AquaBorder
            return layoutAndGetText(g, b, null, alternateContentInsets, viewRect, iconRect, textRect);
        }

        // there is no Aqua border, go with what we've got
        return text;
    }

    protected void paintIcon(final Graphics g, final AbstractButton b, final Rectangle localIconRect) {
        final ButtonModel model = b.getModel();
        Icon icon = b.getIcon();
        Icon tmpIcon = null;

        if (icon == null) return;

        Icon selectedIcon = null;

        // the fallback icon should be based on the selected state
        if (model.isSelected()) {
            selectedIcon = b.getSelectedIcon();
            if (selectedIcon != null) {
                icon = selectedIcon;
            }
        }
        if (!model.isEnabled()) {
            if (model.isSelected()) {
                tmpIcon = b.getDisabledSelectedIcon();
               if (tmpIcon == null) {
                   tmpIcon = selectedIcon;
               }
            }
            if (tmpIcon == null) {
                tmpIcon = b.getDisabledIcon();
            }
        } else if (model.isPressed() && model.isArmed()) {
            tmpIcon = b.getPressedIcon();
            if (tmpIcon == null) {
                if (icon instanceof ImageIcon) {
                    tmpIcon = new ImageIcon(AquaUtils.generateSelectedDarkImage(((ImageIcon)icon).getImage()));
                }
            }
        } else if (b.isRolloverEnabled() && model.isRollover()) {
            if (model.isSelected()) {
                tmpIcon = b.getRolloverSelectedIcon();
                if (tmpIcon == null) {
                    tmpIcon = selectedIcon;
                }
            }
            if (tmpIcon == null) {
                tmpIcon = b.getRolloverIcon();
            }
        } else if (model.isSelected()) {
            tmpIcon = b.getSelectedIcon();
        }

        if (model.isEnabled() && b.isFocusOwner() && b.getBorder() instanceof AquaButtonBorder.Toolbar) {
            if (tmpIcon == null) tmpIcon = icon;
            if (tmpIcon instanceof ImageIcon) {
                tmpIcon = AquaFocus.createFocusedIcon(tmpIcon, b, 3);
                tmpIcon.paintIcon(b, g, localIconRect.x - 3, localIconRect.y - 3);
                return;
            }
        }

        if (tmpIcon != null) {
            icon = tmpIcon;
        }

        icon.paintIcon(b, g, localIconRect.x, localIconRect.y);
    }

    /**
     * As of Java 2 platform v 1.4 this method should not be used or overridden.
     * Use the paintText method which takes the AbstractButton argument.
     */
    @Override
    protected void paintText(final Graphics g, final JComponent c, final Rectangle localTextRect, final String text) {
        final AbstractButton b = (AbstractButton)c;
        final ButtonModel model = b.getModel();
        final FontMetrics fm = g.getFontMetrics();
        final int mnemonicIndex = MnemonicHandler.isMnemonicHidden() ? -1 : b.getDisplayedMnemonicIndex();

        /* Draw the Text */
        if (model.isEnabled()) {
            /*** paint the text normally */
            g.setColor(b.getForeground());
        } else {
            /*** paint the text disabled ***/
            g.setColor(defaultDisabledTextColor);
        }
        SwingUtilities2.drawStringUnderlineCharAt(c, g, text, mnemonicIndex, localTextRect.x, localTextRect.y + fm.getAscent());
    }

    @Override
    protected void paintText(final Graphics g, final AbstractButton b, final Rectangle localTextRect, final String text) {
        paintText(g, (JComponent)b, localTextRect, text);
    }

    @Override
    protected void paintButtonPressed(final Graphics g, final AbstractButton b) {
        paint(g, b);
    }

    // Layout Methods
    @Override
    public Dimension getMinimumSize(final JComponent c) {
        final Dimension d = getPreferredSize(c);
        final View v = (View)c.getClientProperty(BasicHTML.propertyKey);
        if (v != null) {
            d.width -= v.getPreferredSpan(View.X_AXIS) - v.getMinimumSpan(View.X_AXIS);
        }
        return d;
    }

    @Override
    public Dimension getPreferredSize(final JComponent c) {
        final AbstractButton b = (AbstractButton)c;

        // fix for Radar #3134273
        final Dimension d = BasicGraphicsUtils.getPreferredButtonSize(b, b.getIconTextGap());
        if (d == null) return null;

        final Border border = b.getBorder();
        if (border instanceof AquaButtonBorder) {
            ((AquaButtonBorder)border).alterPreferredSize(d);
        }

        return d;
    }

    @Override
    public Dimension getMaximumSize(final JComponent c) {
        final Dimension d = getPreferredSize(c);

        final View v = (View)c.getClientProperty(BasicHTML.propertyKey);
        if (v != null) {
            d.width += v.getMaximumSpan(View.X_AXIS) - v.getPreferredSpan(View.X_AXIS);
        }

        return d;
    }

    private static final RecyclableSingleton<AquaHierarchyButtonListener> fHierListener = new RecyclableSingletonFromDefaultConstructor<AquaHierarchyButtonListener>(AquaHierarchyButtonListener.class);
    static AquaHierarchyButtonListener getAquaHierarchyButtonListener() {
        return fHierListener.get();
    }

    // We need to know when ordinary JButtons are put on JToolbars, but not JComboBoxButtons
    // JToggleButtons always have the same border

    private boolean shouldInstallHierListener(final AbstractButton b) {
        return  (b instanceof JButton || b instanceof JToggleButton && !(b instanceof AquaComboBoxButton) && !(b instanceof JCheckBox) && !(b instanceof JRadioButton));
    }

    protected void installHierListener(final AbstractButton b) {
        if (shouldInstallHierListener(b)) {
            // super put the listener in the button's client properties
            b.addHierarchyListener(getAquaHierarchyButtonListener());
        }
    }

    protected void uninstallHierListener(final AbstractButton b) {
        if (shouldInstallHierListener(b)) {
            b.removeHierarchyListener(getAquaHierarchyButtonListener());
        }
    }

    static final class AquaHierarchyButtonListener implements HierarchyListener {
        // Every time a hierarchy is changed we need to check if the button is moved on or from
        // the toolbar. If that is the case, we need to re-set the border of the button.
        @Override
        public void hierarchyChanged(final HierarchyEvent e) {
            if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) == 0) return;

            final Object o = e.getSource();
            if (!(o instanceof AbstractButton)) return;

            final AbstractButton b = (AbstractButton)o;
            final ButtonUI ui = b.getUI();
            if (!(ui instanceof AquaButtonUI)) return;

            if (!(b.getBorder() instanceof UIResource)) return; // if the border is not one of ours, or null
            ((AquaButtonUI)ui).setThemeBorder(b);
        }
    }

    final class AquaButtonListener extends BasicButtonListener {
        protected final AbstractButton b;

        public AquaButtonListener(final AbstractButton b) {
            super(b);
            this.b = b;
        }

        @Override
        public void focusGained(final FocusEvent e) {
            ((Component)e.getSource()).repaint();
        }

        @Override
        public void focusLost(final FocusEvent e) {
            // 10-06-03 VL: [Radar 3187049]
            // If focusLost arrives while the button has been left-clicked this would disarm the button,
            // causing actionPerformed not to fire on mouse release!
            //b.getModel().setArmed(false);
            b.getModel().setPressed(false);
            ((Component)e.getSource()).repaint();
        }

        @Override
        public void propertyChange(final PropertyChangeEvent e) {
            super.propertyChange(e);

            final String propertyName = e.getPropertyName();

            // Repaint the button, since its border needs to handle the new state.
            if (AquaFocusHandler.FRAME_ACTIVE_PROPERTY.equals(propertyName)) {
                b.repaint();
                return;
            }

            if ("icon".equals(propertyName) || "text".equals(propertyName)) {
                setThemeBorder(b);
                return;
            }

            if (BUTTON_TYPE.equals(propertyName)) {
                // Forced border types
                final String value = (String)e.getNewValue();

                final Border border = AquaButtonExtendedTypes.getBorderForPosition(b, value, b.getClientProperty(SEGMENTED_BUTTON_POSITION));
                if (border != null) {
                    b.setBorder(border);
                }

                return;
            }

            if (SEGMENTED_BUTTON_POSITION.equals(propertyName)) {
                final Border border = b.getBorder();
                if (!(border instanceof AquaBorder)) return;

                b.setBorder(AquaButtonExtendedTypes.getBorderForPosition(b, b.getClientProperty(BUTTON_TYPE), e.getNewValue()));
            }

            if ("componentOrientation".equals(propertyName)) {
                final Border border = b.getBorder();
                if (!(border instanceof AquaBorder)) return;

                Object buttonType = b.getClientProperty(BUTTON_TYPE);
                Object buttonPosition = b.getClientProperty(SEGMENTED_BUTTON_POSITION);
                if (buttonType != null && buttonPosition != null) {
                    b.setBorder(AquaButtonExtendedTypes.getBorderForPosition(b, buttonType, buttonPosition));
                }
            }
        }
    }
}
