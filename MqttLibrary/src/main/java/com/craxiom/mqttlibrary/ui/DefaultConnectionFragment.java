package com.craxiom.mqttlibrary.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewStub;

/**
 * A connection fragment without additional fields; use this class for any UI's that don't need
 * extra configuration in their MQTT connection.
 *
 * @since 0.1.0
 */
public abstract class DefaultConnectionFragment<T extends AConnectionFragment.ServiceBinder> extends AConnectionFragment<T>
{
    @Override
    protected void readMdmConfigAdditionalProperties(Bundle mdmProperties)
    {
        // no-op
    }

    @Override
    protected void readUIAdditionalFields()
    {
        // no-op
    }

    @Override
    protected void storeAdditionalParameters(SharedPreferences.Editor edit)
    {
        // no-op
    }

    @Override
    protected void restoreAdditionalParameters(SharedPreferences preferences)
    {
        // no-op
    }

    @Override
    protected void inflateAdditionalFieldsViewStub(LayoutInflater inflater, ViewStub viewStub)
    {
        // no-op
    }
}
