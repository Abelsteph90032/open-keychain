/*
 * Copyright (C) 2017 Schürmann & Breitmoser GbR
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui;


import java.util.ArrayList;
import java.util.Iterator;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.model.SubKey;
import org.sufficientlysecure.keychain.model.SubKey.UnifiedKeyInfo;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKey.SecretKeyType;
import org.sufficientlysecure.keychain.provider.KeyRepository;
import org.sufficientlysecure.keychain.provider.KeyRepository.NotFoundException;
import org.sufficientlysecure.keychain.service.input.RequiredInputParcel;
import org.sufficientlysecure.keychain.ui.util.Notify;
import org.sufficientlysecure.keychain.util.FileHelper;

public class BackupRestoreFragment extends Fragment {

    // masterKeyId & subKeyId for multi-key export
    private Iterator<Pair<Long, Long>> mIdsForRepeatAskPassphrase;

    private static final int REQUEST_REPEAT_PASSPHRASE = 0x00007002;
    private static final int REQUEST_CODE_INPUT = 0x00007003;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.backup_restore_fragment, container, false);

        View backupAll = view.findViewById(R.id.backup_all);
        View backupPublicKeys = view.findViewById(R.id.backup_public_keys);
        final View restore = view.findViewById(R.id.restore);

        backupAll.setOnClickListener(v -> exportToFile(true));
        backupPublicKeys.setOnClickListener(v -> exportToFile(false));
        restore.setOnClickListener(v -> restore());

        return view;
    }

    private void exportToFile(boolean includeSecretKeys) {
        FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }

        if (!includeSecretKeys) {
            startBackup(false);
            return;
        }

        KeyRepository keyRepository = KeyRepository.create(requireContext());

        // This can probably be optimized quite a bit.
        // Typically there are only few secret keys though, so it doesn't really matter.

        new AsyncTask<Void, Void, ArrayList<Pair<Long, Long>>>() {
            @Override
            protected ArrayList<Pair<Long,Long>> doInBackground(Void... ignored) {
                KeyRepository keyRepository = KeyRepository.create(requireContext());
                ArrayList<Pair<Long, Long>> askPassphraseIds = new ArrayList<>();
                for (UnifiedKeyInfo keyInfo : keyRepository.getAllUnifiedKeyInfoWithSecret()) {
                    long masterKeyId = keyInfo.master_key_id();
                    SecretKeyType secretKeyType;
                    try {
                        secretKeyType = keyRepository.getSecretKeyType(keyInfo.master_key_id());
                    } catch (NotFoundException e) {
                        throw new IllegalStateException("Error: no secret key type for secret key!");
                    }
                    switch (secretKeyType) {
                        // all of these make no sense to ask
                        case PASSPHRASE_EMPTY:
                        case DIVERT_TO_CARD:
                        case UNAVAILABLE:
                            continue;
                        case GNU_DUMMY: {
                            Long subKeyId = getFirstSubKeyWithPassphrase(masterKeyId);
                            if(subKeyId != null) {
                                askPassphraseIds.add(new Pair<>(masterKeyId, subKeyId));
                            }
                            continue;
                        }
                        default: {
                            askPassphraseIds.add(new Pair<>(masterKeyId, masterKeyId));
                        }
                    }
                }
                return askPassphraseIds;
            }

            private Long getFirstSubKeyWithPassphrase(long masterKeyId) {
                for (SubKey subKey : keyRepository.getSubKeysByMasterKeyId(masterKeyId)) {
                    switch (subKey.has_secret()) {
                        case PASSPHRASE_EMPTY:
                        case DIVERT_TO_CARD:
                        case UNAVAILABLE:
                            return null;
                        case GNU_DUMMY:
                            continue;
                        default: {
                            return subKey.key_id();
                        }
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(ArrayList<Pair<Long, Long>> askPassphraseIds) {
                super.onPostExecute(askPassphraseIds);
                FragmentActivity activity = getActivity();
                if (activity == null) {
                    return;
                }

                mIdsForRepeatAskPassphrase = askPassphraseIds.iterator();

                if (mIdsForRepeatAskPassphrase.hasNext()) {
                    startPassphraseActivity();
                    return;
                }

                startBackup(true);
            }

        }.execute();
    }

    private void startPassphraseActivity() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        Intent intent = new Intent(activity, PassphraseDialogActivity.class);
        Pair<Long, Long> keyPair = mIdsForRepeatAskPassphrase.next();
        long masterKeyId = keyPair.first;
        long subKeyId = keyPair.second;
        RequiredInputParcel requiredInput =
                RequiredInputParcel.createRequiredDecryptPassphrase(masterKeyId, subKeyId);
        requiredInput.mSkipCaching = true;
        intent.putExtra(PassphraseDialogActivity.EXTRA_REQUIRED_INPUT, requiredInput);
        startActivityForResult(intent, REQUEST_REPEAT_PASSPHRASE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_REPEAT_PASSPHRASE: {
                if (resultCode != Activity.RESULT_OK) {
                    return;
                }
                if (mIdsForRepeatAskPassphrase.hasNext()) {
                    startPassphraseActivity();
                    return;
                }

                startBackup(true);

                break;
            }

            case REQUEST_CODE_INPUT: {
                if (resultCode != Activity.RESULT_OK || data == null) {
                    return;
                }

                Uri uri = data.getData();
                if (uri == null) {
                    Notify.create(getActivity(), R.string.no_file_selected, Notify.Style.ERROR).show();
                    return;
                }

                Intent intent = new Intent(getActivity(), DecryptActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(uri);
                startActivity(intent);
                break;
            }

            default: {
                super.onActivityResult(requestCode, resultCode, data);
            }
        }
    }

    private void startBackup(boolean exportSecret) {
        Intent intent = new Intent(getActivity(), BackupActivity.class);
        intent.putExtra(BackupActivity.EXTRA_SECRET, exportSecret);
        startActivity(intent);
    }

    private void restore() {
        FileHelper.openDocument(this, "*/*", false, REQUEST_CODE_INPUT);
    }

}
