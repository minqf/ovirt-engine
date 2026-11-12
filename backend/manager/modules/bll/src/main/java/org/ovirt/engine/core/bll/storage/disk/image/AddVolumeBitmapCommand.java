package org.ovirt.engine.core.bll.storage.disk.image;

import org.ovirt.engine.core.bll.InternalCommandAttribute;
import org.ovirt.engine.core.bll.NonTransactiveCommandAttribute;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.common.action.VolumeBitmapCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.compat.Guid;

@NonTransactiveCommandAttribute(forceCompensation = true)
@InternalCommandAttribute
public class AddVolumeBitmapCommand<T extends VolumeBitmapCommandParameters> extends
        VolumeBitmapCommandBase<T> {

    public AddVolumeBitmapCommand(Guid commandId) {
        super(commandId);
    }

    public AddVolumeBitmapCommand(T parameters, CommandContext commandContext) {
        super(parameters, commandContext);
    }

    @Override
    protected VDSCommandType getBitmapAction() {
        return VDSCommandType.AddVolumeBitmap;
    }
}
