# GitHub bootstrap notes

The initial Windows bootstrap script now avoids using `gh repo view` as an existence probe. That command returns a non-zero status when the repository is absent, and some PowerShell configurations can promote its stderr stream to a terminating error. The script instead lists the authenticated owner's repositories and checks for an exact name match.

The local registry validation also has a PowerShell implementation, so Windows users do not need to install Python just to validate the profiles and source catalogue. CI continues to use the dependency-free Python validator.

## Windows setup

1. Install Git and GitHub CLI (`gh`).
2. Run `gh auth login` and authenticate as `irman100`.
3. Configure Git author identity if needed:
   `git config --global user.name "Your Name"`
   `git config --global user.email "you@example.com"`
4. From `D:\RetroMobileWorld`, run:
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\validate_registry.ps1`
5. Then run:
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\BOOTSTRAP-WINDOWS.ps1`

The bootstrap targets a private repository named `irman100/RetroMobileWorld`. It refuses to reuse a folder containing `.git` or overwrite an existing remote repository.
