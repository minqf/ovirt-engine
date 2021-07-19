package org.ovirt.engine.core.bll.scheduling.commands;

import javax.inject.Inject;

import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.scheduling.parameters.AffinityGroupMemberChangeParameters;
import org.ovirt.engine.core.dao.LabelDao;
import org.ovirt.engine.core.dao.scheduling.AffinityGroupDao;

public class RemoveHostLabelFromAffinityGroupCommand <T extends AffinityGroupMemberChangeParameters>
        extends EditAffinityGroupCommand<T> {
    @Inject
    private AffinityGroupDao affinityGroupDao;

    @Inject
    private LabelDao labelDao;

    public RemoveHostLabelFromAffinityGroupCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected boolean validate() {
        if (getAffinityGroup() == null) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_INVALID_AFFINITY_GROUP_ID);
        }
        if (labelDao.get(getParameters().getEntityId()) == null) {
            return failValidation(EngineMessage.AFFINITY_LABEL_NOT_EXISTS);
        }
        if (!getAffinityGroup().getVmIds().contains(getParameters().getEntityId())) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_INVALID_ENTITY_FOR_AFFINITY_GROUP, String
                    .format("$entity %s", Entity_LABEL));
        }
        return true;
    }

    @Override
    protected void executeCommand() {
        affinityGroupDao.deleteAffinityHostLabel(getParameters().getAffinityGroupId(), getParameters().getEntityId());
        setSucceeded(true);
    }
}
