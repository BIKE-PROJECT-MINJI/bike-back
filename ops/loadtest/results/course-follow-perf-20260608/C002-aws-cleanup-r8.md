# AWS cleanup receipt

- prefix: bike-ulw-loadtest-20260608-course-follow-r8
- region: ap-northeast-2
- instance_id: i-09f4eab913b6558c0
- security_group_id: sg-06d2a24eedb917791
- key_name: bike-ulw-loadtest-20260608-course-follow-r8-key
- exit_status: 0
- cleanup_finished_at: 2026-06-08T13:51:24+09:00

## cleanup verification
- remote_secret_env_removed: 0
- terminate_instances_exit: 0
- instance_terminated_wait_exit: 0
- instance_state_after_cleanup: terminated
- delete_security_group_exit: 0
- security_group_describe_after_delete_exit: 254
- delete_key_pair_exit: 0
- key_pair_describe_after_delete_exit: 254
- local_tmp_removed_exit: 0
