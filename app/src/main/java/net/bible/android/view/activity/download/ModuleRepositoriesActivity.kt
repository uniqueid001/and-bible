package net.bible.android.view.activity.download

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.primarySurface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.bible.android.activity.R
import net.bible.android.view.util.UiUtils.actionbarBackgroundColor
import net.bible.service.download.DownloadManager
import net.bible.service.download.RepoFactory
import net.bible.service.download.urlPrefix

class ModuleRepositoriesActivity
    : ComponentActivity()
    {
    override fun onCreate(savedInstanceState: Bundle?)
        {
        super.onCreate(savedInstanceState)
        val repoFactory = RepoFactory(DownloadManager(null))
        val repositories = repoFactory.getRepositoryInfo()
        setContent {
            AppTheme {
                ActivityModuleRepositories(
                    title = title.toString(), // apply the activity's title declared in the manifest
                    moduleRepositories = repositories,
                    onNavIconClick = { onBackPressed() },
                    // apply AndBible's programmatically-selected color
                    // (which is not automatically handled by the AppCompat Theme and Adapter)
                    andBibleActionBarBackground = Color(actionbarBackgroundColor()),
                    )
                }
            }
        } // onCreate()

    } // class

/**
 * Gather the URL used by the Installer associated with each Repository.
 */
private fun RepoFactory.getRepositoryInfo()
    = repositories.mapNotNull { repo ->
        val installer = downloadManager.getInstallerFor(repo)
        installer?.urlPrefix?.let { url ->
            RepoInfo(repo.repoName, url, repo.organization)
            }
        }

data class RepoInfo(
    val name: String,
    val uri: String,
    val organization: String,
    )

@Composable
private fun ActivityModuleRepositories(
    title: String,
    andBibleActionBarBackground: Color = MaterialTheme.colors.primarySurface,
    moduleRepositories: List<RepoInfo> = emptyList(),
    onNavIconClick: ()->Unit = {},
    ) {
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(title) },
            backgroundColor = andBibleActionBarBackground,
            navigationIcon = {
                IconButton(onClick = onNavIconClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "")
                    } },
            )},
        ) {
        Surface(modifier = Modifier.padding(it)) {
            ModuleRepositoriesList(moduleRepositories = moduleRepositories)
            }
        }
    }

@Composable
private fun ModuleRepositoriesList(
    moduleRepositories: List<RepoInfo>,
    modifier: Modifier = Modifier,
    ) {
    LazyColumn(modifier = modifier) {
        items(moduleRepositories, key = { it.name }) {
            ModuleRepository(it)
            }
        }
    }

@Composable
private fun ModuleRepository(
    repository: RepoInfo,
    modifier: Modifier = Modifier,
    ) {
    Card(
        shape = RoundedCornerShape(13.dp),
        elevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
            modifier = modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
            fun Modifier.indent(level: Int) = padding(start = 24.dp * level)
            Text(repository.name, style = MaterialTheme.typography.h5)
            Text(stringResource(R.string.repository_location_heading), style = MaterialTheme.typography.h6, modifier = Modifier.indent(1))
            Text(repository.uri, style = MaterialTheme.typography.body1, modifier = Modifier.indent(2))
            Text(stringResource(R.string.repository_organization_heading), style = MaterialTheme.typography.h6, modifier = Modifier.indent(1))
            Text(repository.organization, style = MaterialTheme.typography.body1, modifier = Modifier.indent(2))
            }
        }
    }

// ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~
// ☟☟☟☟☟ 📸 ☟☟☟☟☟ 📸 ☟☟☟☟☟ 📸 ☟☟☟☟☟ 📸 ☟☟☟☟☟ 📸 ☟☟☟☟☟ 📸 ☟☟☟☟☟ 📸 ☟☟☟☟☟ 📸 ☟☟☟☟☟
// ☟☟☟☟☟ 🖼 ☟☟☟☟☟ 🖼 ☟☟☟☟☟ 🖼 ☟☟☟☟☟ 🖼 ☟☟☟☟☟ 🖼 ☟☟☟☟☟ 🖼 ☟☟☟☟☟ 🖼 ☟☟☟☟☟ 🖼 ☟☟☟☟☟
// ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~ ~~~~~

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES, heightDp = 400)
@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_NO,  heightDp = 400)
@Composable
private fun ActivityPreview() {
    AppTheme {
        ActivityModuleRepositories(
            title = "Activity Title",
            moduleRepositories = previewRepositories)
        }
    }

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
//@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun ModuleRepositoryPreview() {
    AppTheme {
        ModuleRepository( previewRepository )
        }
    }

@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
//@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_NO)
@Composable
private fun ModuleRepositoriesPreview() {
    AppTheme {
        ModuleRepositoriesList(moduleRepositories = previewRepositories)
        }
    }

private val previewRepositories by lazy { listOf(
    RepoInfo("AndBible", "https://example.com/", "AndBible Project"),
    RepoInfo("Name2", "https://example2.com/", "Someone Else"),
    ) }
private val previewRepository by lazy { previewRepositories[0] }
