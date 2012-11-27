package org.ovirt.engine.ui.common.widget.dialog;

import org.ovirt.engine.ui.common.view.popup.FocusableComponentsContainer;
import org.ovirt.engine.ui.uicommonweb.UICommand;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.resources.client.CssResource;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiChild;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.ButtonBase;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

public class SimpleDialogPanel extends AbstractDialogPanel implements FocusableComponentsContainer {

    interface WidgetUiBinder extends UiBinder<Widget, SimpleDialogPanel> {
        WidgetUiBinder uiBinder = GWT.create(WidgetUiBinder.class);
    }

    protected interface Style extends CssResource {
        String footerButton();

        String contentWidget();
    }

    @UiField
    SimplePanel headerCenterPanel;

    @UiField
    SimplePanel headerLeftPanel;

    @UiField
    SimplePanel contentPanel;

    @UiField
    FlowPanel footerButtonPanel;

    @UiField
    FlowPanel footerStatusPanel;

    @UiField
    ButtonBase helpButton;

    @UiField
    Style style;

    UICommand helpCommand;

    public SimpleDialogPanel() {
        setWidget(WidgetUiBinder.uiBinder.createAndBindUi(this));
        getElement().getStyle().setZIndex(1);
        addHelpButtonHandler();
    }

    @Override
    @UiChild(tagname = "header", limit = 1)
    public void setHeader(Widget widget) {
        headerCenterPanel.setWidget(widget);
    }

    @UiChild(tagname = "logo", limit = 1)
    public void setLogo(Widget widget) {
        headerLeftPanel.setWidget(widget);
    }

    @Override
    @UiChild(tagname = "content", limit = 1)
    public void setContent(Widget widget) {
        contentPanel.setWidget(widget);
        widget.addStyleName(style.contentWidget());
    }

    @Override
    public Widget getContent() {
        return contentPanel.getWidget();
    }

    @Override
    @UiChild(tagname = "footerButton")
    public void addFooterButton(Widget button) {
        button.addStyleName(style.footerButton());
        footerButtonPanel.add(button);
    }

    @Override
    public void addStatusWidget(Widget widget) {
        footerStatusPanel.add(widget);
    }

    @Override
    public void removeFooterButtons() {
        footerButtonPanel.clear();
    }

    @Override
    public void setFooterPanelVisible(boolean visible) {
        footerButtonPanel.setVisible(visible);
    }

    @Override
    public void addContentStyleName(String styleName) {
        contentPanel.addStyleName(styleName);
    }

    private void addHelpButtonHandler() {
        helpButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                helpCommand.Execute();
            }
        });
    }

    @Override
    public void setHelpCommand(UICommand command) {
        helpCommand = command;
        helpButton.setVisible(command != null);
    }

    public int setTabIndexes(int nextTabIndex) {
        int nbWidgets = footerButtonPanel.getWidgetCount();
        for (int i=nbWidgets-1; i>=0; --i) {
            Widget iWidget = footerButtonPanel.getWidget(i);
            if (iWidget instanceof FocusableComponentsContainer)
                nextTabIndex = ((FocusableComponentsContainer) iWidget).setTabIndexes(nextTabIndex);
        }
        return nextTabIndex;
    }
}
