# Contributing to CityAndRuralConjunction

Use this guide to contribute coursework and demonstrate the version control workflow in the supplied worksheet. Work on a task branch, keep commits focused, and have another group member review the proposed changes.

## Worksheet mapping

| Worksheet task | Application to this repository |
| --- | --- |
| 1. Log in to GitLab | Sign in to GitHub with your own account. |
| 2. Create a repository | Use the existing `1764868036-cell/CityAndRuralConjunction` repository and its README. |
| 3. Add group members | The owner manages GitHub collaborators. Each invited member must accept the invitation. |
| 4. Clone and push changes | Clone this repository, make a local commit, and push a task branch. |
| 5. Manage collaboration, optional | Use GitHub pull requests for review and Issues for task tracking. |

GitLab's role names do not map directly to GitHub's personal-repository permissions. Access and platform acceptance should be checked against the course requirements.

## Configure your local identity

Run these commands inside your clone, replacing the example values with your own name and email. You can use your GitHub-provided no-reply email address.

```bash
git config user.name "Your name"
git config user.email "Your GitHub email"
```

These settings identify commit authors; they do not sign you in or grant repository access. Authenticate HTTPS operations through GitHub CLI, Git Credential Manager, or an appropriate personal access token. A regular GitHub account password is not accepted for Git HTTPS authentication. Never put credentials in committed files.

## Start a task

Check the working tree first. Commit or otherwise safely preserve unfinished work before switching branches.

```bash
git status
git switch main
git pull --ff-only origin main
git switch -c docs/your-task-name
```

Replace `docs/your-task-name` with a new name describing your task. The `--ff-only` option stops if the local and remote histories have diverged, so you can inspect the situation before deciding how to reconcile it.

## Inspect and commit changes

For example, after editing and saving `README.md`:

```bash
git status
git diff
git add README.md
git diff --cached
git commit -m "docs: clarify project introduction"
```

`git add` selects changes for a commit. `git commit` creates the version record locally. Replace `README.md` with the files that belong to your task. The worksheet's `git add .` stages all changes under the current directory, so inspect the file list before using it.

For Word documents, PDFs, diagrams, and other files whose changes are not readable in a text diff, open the updated file and check it in its usual application before committing. Do not commit temporary lock files, passwords, or unrelated software installations.

## Push the branch

```bash
git push -u origin docs/your-task-name
```

Use the same branch name you created earlier. `origin` is the remote configured by cloning; `-u` associates the local branch with the remote branch. Later commits on that branch can normally be uploaded with `git push`.

## Open and review a pull request

1. Open the repository on GitHub.
2. Choose **Pull requests**, then **New pull request**, or use **Compare & pull request** after a push.
3. Set **base** to `main` and **compare** to your task branch.
4. Give the pull request a clear title and explain what changed and how you checked it.
5. Ask another group member to review the changed files.
6. Address feedback with additional commits on the same branch.
7. After review, a member with the required permission can merge the pull request.

Opening a pull request does not mean the change has been approved or merged. Record those steps separately when presenting coursework evidence.

After your pull request has been merged and your working tree is clean:

```bash
git switch main
git pull --ff-only origin main
```

Start the next task on a new branch from the updated `main`.

## Track group tasks with Issues

When Issues is enabled, choose **Issues**, then **New issue**. Describe the required output, completion criteria, and intended deadline. A member with the required permission can assign the issue to a group member. Link the relevant issue in a pull request so the task and implementation can be traced together.

## Evidence for the worksheet

Keep the repository URL, access list, clone output, commit identifier, pushed branch, and pull request URL. Report a step as complete only when its result has been verified. An existing repository and existing accepted collaborators can provide evidence for those tasks; do not claim they were newly created or invited during this exercise.

## References

- [Cloning a repository](https://docs.github.com/en/repositories/creating-and-managing-repositories/cloning-a-repository)
- [Personal repository permissions](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/permission-levels-for-a-personal-account-repository)
- [Authentication to GitHub](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-authentication-to-github)
- [Creating a pull request](https://docs.github.com/en/pull-requests/how-tos/create-pull-requests/creating-a-pull-request)
- [Creating an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-an-issue)
